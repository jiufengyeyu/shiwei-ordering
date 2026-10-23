package com.sky.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.vo.DishVO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 二次开发任务1：用户端菜品列表多级缓存
 *
 * 读取链路：L1 Caffeine(30s) → L2 Redis(30min + 随机 0~10min) → 布隆过滤器 → 互斥锁回源数据库
 *
 * 解决的三大问题：
 * 1. 穿透：布隆过滤器拦截一定不存在的分类 id；空结果也缓存 60s（空值缓存）；
 * 2. 击穿：热点分类缓存过期瞬间，SETNX 互斥锁只放一个线程回源重建，其余线程等待后读缓存；
 * 3. 雪崩：Redis 过期时间 = 30 分钟基础值 + 随机 0~10 分钟，同批 key 不会同时过期。
 *
 * 一致性：管理端菜品增删改时调用 evictAll() 清空 L1 并删除全部 L2 key
 * （修复原代码完全缺失缓存清理、用户端永远读到旧数据的缺陷）。
 */
@Component
@Slf4j
public class UserDishCacheService {

    /** L2 Redis key 前缀（v2：与原项目 JDK 序列化的 dishCache:: 老格式隔离） */
    private static final String L2_KEY_PREFIX = "dishCache:v2:";
    /** 互斥锁 key 前缀 */
    private static final String LOCK_KEY_PREFIX = "lock:userDish:";

    /** L1 本地缓存：存 JSON 字符串，30 秒过期，最多 200 个分类（内存友好） */
    private final Cache<String, String> localCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(200)
            .build();

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CategoryBloomFilter categoryBloomFilter;
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 多级缓存读取
     * @param categoryId 分类 id
     * @param loader     缓存未命中时的数据库回源函数
     */
    public List<DishVO> listWithCache(Long categoryId, Supplier<List<DishVO>> loader) {
        String key = L2_KEY_PREFIX + categoryId;

        // 1. L1 本地缓存：无网络开销，纳秒级
        String l1 = localCache.getIfPresent(key);
        if (l1 != null) {
            return deserialize(l1);
        }

        // 2. L2 Redis：一次网络往返
        String l2 = stringRedisTemplate.opsForValue().get(key);
        if (l2 != null) {
            localCache.put(key, l2);
            return deserialize(l2);
        }

        // 3. 布隆过滤器防穿透：一定不存在的分类 id 直接返回空列表，并缓存空值 60s
        if (!categoryBloomFilter.mightContain(categoryId)) {
            log.debug("布隆过滤器拦截不存在的分类 id: {}", categoryId);
            String empty = serialize(Collections.emptyList());
            localCache.put(key, empty);
            stringRedisTemplate.opsForValue().set(key, empty, Duration.ofSeconds(60));
            return Collections.emptyList();
        }

        // 4. 互斥锁防击穿：只放一个线程回源数据库
        String lockKey = LOCK_KEY_PREFIX + categoryId;
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
        if (Boolean.TRUE.equals(locked)) {
            try {
                // 双重检查：拿锁期间可能别的线程已完成重建
                l2 = stringRedisTemplate.opsForValue().get(key);
                if (l2 != null) {
                    localCache.put(key, l2);
                    return deserialize(l2);
                }
                List<DishVO> result = loader.get();
                String json = serialize(result);
                // 5. 写 L2：TTL 30 分钟 + 随机 0~10 分钟（防雪崩）
                long ttlSeconds = TimeUnit.MINUTES.toSeconds(30) + ThreadLocalRandom.current().nextLong(600);
                stringRedisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
                localCache.put(key, json);
                return result;
            } finally {
                stringRedisTemplate.delete(lockKey);
            }
        } else {
            // 没抢到锁：稍等后重读 L2（其他线程正在重建）
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return loader.get();
            }
            l2 = stringRedisTemplate.opsForValue().get(key);
            if (l2 != null) {
                localCache.put(key, l2);
                return deserialize(l2);
            }
            // 降级：锁等待后仍无数据，直接回源（宁可短暂压库，也不返回空数据）
            return loader.get();
        }
    }

    /**
     * 管理端菜品增删改后调用：清空 L1 全部条目 + 删除 L2 全部 key
     * （管理端写操作频率低，全清是最简单且保证一致性的策略）
     */
    public void evictAll() {
        localCache.invalidateAll();
        Set<String> keys = stringRedisTemplate.keys(L2_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        log.info("用户端菜品多级缓存已全量清理（L1 + L2 共 {} 个 key）", keys == null ? 0 : keys.size());
    }

    private String serialize(List<DishVO> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("菜品列表序列化失败", e);
        }
    }

    private List<DishVO> deserialize(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<DishVO>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("菜品列表反序列化失败", e);
        }
    }
}
