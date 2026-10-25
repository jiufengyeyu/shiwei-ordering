package com.sky.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.exception.BaseException;
import com.sky.vo.DishVO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    /**
     * L1 本地缓存：直接存活对象（List&lt;DishVO&gt;），30 秒过期，最多 200 个分类。
     * 性能优化（2026-10-25）：早期版本存 JSON 字符串，命中后每请求都要 Jackson 解析 50KB 再序列化回去；
     * 改为存活对象后命中路径零解析零分配（与 Spring Cache CaffeineCacheManager 语义一致）。
     * 约定：调用方对返回的 List 只读不修改（缓存对象被所有线程共享）。
     */
    private final Cache<String, List<DishVO>> localCache = Caffeine.newBuilder()
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

        // 1. L1 本地缓存：无网络开销，纳秒级，命中零解析
        List<DishVO> l1 = localCache.getIfPresent(key);
        if (l1 != null) {
            return l1;
        }

        // 2. L2 Redis：一次网络往返，解析一次后以对象形式回填 L1
        String l2 = stringRedisTemplate.opsForValue().get(key);
        if (l2 != null) {
            List<DishVO> obj = deserialize(l2);
            localCache.put(key, obj);
            return obj;
        }

        // 3. 布隆过滤器防穿透：一定不存在的分类 id 直接返回空列表，并缓存空值 60s
        if (!categoryBloomFilter.mightContain(categoryId)) {
            log.debug("布隆过滤器拦截不存在的分类 id: {}", categoryId);
            List<DishVO> empty = Collections.emptyList();
            localCache.put(key, empty);
            stringRedisTemplate.opsForValue().set(key, serialize(empty), Duration.ofSeconds(60));
            return empty;
        }

        // 4. 互斥锁防击穿：只放一个线程回源数据库。
        //    锁值用随机 UUID（替代固定 "1"）：释放时校验属主，防止线程 A 执行超时锁过期后误删线程 B 的锁
        String lockKey = LOCK_KEY_PREFIX + categoryId;
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10));
        if (Boolean.TRUE.equals(locked)) {
            try {
                // 双重检查：拿锁期间可能别的线程已完成重建
                l2 = stringRedisTemplate.opsForValue().get(key);
                if (l2 != null) {
                    List<DishVO> rebuilt = deserialize(l2);
                    localCache.put(key, rebuilt);
                    return rebuilt;
                }
                List<DishVO> result = loader.get();
                // 5. 写 L2：TTL 30 分钟 + 随机 0~10 分钟（防雪崩）；L1 存对象
                long ttlSeconds = TimeUnit.MINUTES.toSeconds(30) + ThreadLocalRandom.current().nextLong(600);
                stringRedisTemplate.opsForValue().set(key, serialize(result), Duration.ofSeconds(ttlSeconds));
                localCache.put(key, result);
                return result;
            } finally {
                // 释放锁前校验属主（Lua 保证"比对+删除"原子执行），只删自己加的锁
                releaseLock(lockKey, lockValue);
            }
        } else {
            // 没抢到锁：循环重读 L2，等持锁线程重建完成（最多等 500ms，每 100ms 探测一次）
            for (int i = 0; i < 5; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                l2 = stringRedisTemplate.opsForValue().get(key);
                if (l2 != null) {
                    List<DishVO> obj = deserialize(l2);
                    localCache.put(key, obj);
                    return obj;
                }
            }
            // 等 500ms 仍无数据：说明回源异常地慢（数据库大概率已出问题）。
            // 绝不绕过互斥锁自己去查库——那会制造第二个回源入口，成批线程压垮数据库。
            // 快速失败：个别用户看到"繁忙"提示重试即可恢复，好过无保护查库引发击穿。
            throw new BaseException("系统繁忙，请稍后再试");
        }
    }

    /**
     * 管理端菜品增删改后按分类精准清理（替代早期 evictAll 全清）：
     * 改哪个分类就清哪个分类的 L1 + L2，无关分类的缓存不受影响，避免不必要的回源。
     * 换分类的菜品新旧两个分类都要清；批量删除收集去重后传入。
     */
    public void evictCategory(Long... categoryIds) {
        for (Long categoryId : categoryIds) {
            if (categoryId == null) {
                continue;
            }
            String key = L2_KEY_PREFIX + categoryId;
            localCache.invalidate(key);
            stringRedisTemplate.delete(key);
        }
        log.info("用户端菜品缓存已按分类精准清理: {}", Arrays.toString(categoryIds));
    }

    /** 释放锁：Lua 保证"比对属主 + 删除"原子执行，避免误删他人的锁 */
    private void releaseLock(String lockKey, String lockValue) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                Long.class);
        stringRedisTemplate.execute(script, Collections.singletonList(lockKey), lockValue);
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
