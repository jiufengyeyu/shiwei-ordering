package com.sky.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.concurrent.TimeUnit;

/**
 * 二次开发任务1：缓存管理器配置
 *
 * 双缓存管理器设计：
 * 1. redisCacheManager（@Primary）：用户端既有缓存（setmealCache 等）继续走 Redis，
 *    保持与原项目行为一致；
 * 2. caffeineCacheManager：管理端高频分页查询（adminDishPage）专用本地缓存，
 *    需在 @Cacheable/@CacheEvict 上显式指定 cacheManager = "caffeineCacheManager"。
 *
 * 为什么管理端分页用 Caffeine：报表类查询参数组合多变、时效要求低（60s 兜底 + 写操作精准失效），
 * 本地内存读远快于 Redis 网络往返，且不占用 Redis 带宽。
 */
@Configuration
@Slf4j
public class CacheConfig {

    /**
     * 主缓存管理器（Redis）
     * 原项目未显式定义 CacheManager，靠 Spring Boot 自动配置；
     * 现在显式声明并标记 @Primary，保证未指定管理器的 @Cacheable 行为不变。
     */
    @Bean
    @Primary
    public CacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory) {
        log.info("创建 Redis 缓存管理器（主缓存管理器）...");
        return RedisCacheManager.builder(redisConnectionFactory).build();
    }

    /**
     * Caffeine 本地缓存管理器（管理端分页专用）
     * - expireAfterWrite(60s)：写后过期兜底，正常情况靠写操作 @CacheEvict 精准失效
     * - maximumSize(1000)：限制查询参数组合数量，防止内存膨胀
     * - recordStats()：开启命中率统计，便于观察缓存效果
     */
    @Bean
    public CacheManager caffeineCacheManager() {
        log.info("创建 Caffeine 本地缓存管理器（管理端分页专用）...");
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("adminDishPage");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .initialCapacity(64)
                .maximumSize(1000)
                .recordStats());
        return cacheManager;
    }
}
