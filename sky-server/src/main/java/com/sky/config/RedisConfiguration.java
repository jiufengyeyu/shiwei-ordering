package com.sky.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Slf4j
@EnableConfigurationProperties(RedisProperties.class)
public class RedisConfiguration {

    /**
     * 二次开发任务5：显式声明 Lettuce 连接工厂，覆盖 redisson-spring-boot-starter 的自动接管。
     *
     * 背景：Redisson starter 的自动配置会用 RedissonConnectionFactory 替换默认的 Lettuce，
     * 导致缓存（StringRedisTemplate）的 Redis 读写走 Redisson 客户端——其同步命令编解码
     * 开销高于 Lettuce，600 并发满载压测中拖慢全部请求（吞吐腰斩，见压测报告"最终复测"章节）。
     *
     * 原理：Redisson 与 Spring Boot 的 Redis 自动配置都是 @ConditionalOnMissingBean(RedisConnectionFactory.class)
     * ——容器里先注册了本 bean，两者都会退避；RedisTemplate/StringRedisTemplate 按类型注入，
     * 自动拿到这里的 Lettuce 实现。Redisson 客户端仍正常创建（redissonClient bean 不受影响，
     * 分布式锁继续走 Redisson），两者各司其职。
     *
     * 注意 bean 名不能叫 redisConnectionFactory（与 Redisson 同名会在默认禁止 bean 覆盖下启动失败）。
     */
    @Bean
    public LettuceConnectionFactory lettuceConnectionFactory(RedisProperties redisProperties) {
        log.info("创建 Lettuce Redis 连接工厂（覆盖 Redisson 对 RedisTemplate 的接管），host={}", redisProperties.getHost());
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                redisProperties.getHost(), redisProperties.getPort());
        if (redisProperties.getPassword() != null) {
            factory.setPassword(redisProperties.getPassword());
        }
        factory.setDatabase(redisProperties.getDatabase());
        return factory;
    }

    @Bean
    public RedisTemplate redisTemplate(RedisConnectionFactory redisConnectionFactory){
        log.info("开始创建redis模板对象...");
        RedisTemplate redisTemplate = new RedisTemplate();
        //设置redis的连接工厂对象
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        //设置redis key的序列化器
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        return redisTemplate;
    }
}
