package com.sky.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

/**
 * 二次开发任务2：RabbitMQ 订单延迟关单拓扑（TTL + 死信队列方案）
 *
 * 消息流转：下单成功 → 投递到 order.delay.exchange → 进入 order.delay.queue（TTL 15 分钟，无消费者）
 *        → TTL 到期，消息"死信"投递到 order.cancel.exchange → order.cancel.queue
 *        → OrderCancelListener 消费：校验订单仍为"待付款"则取消，否则忽略（幂等）
 *
 * 为什么不用定时任务轮询：每分钟全表扫描 status=1 的订单，扫描量随订单量线性增长，
 * 且关单时机有最大 1 分钟误差；延迟消息在到期瞬间精准触发，数据库零扫描开销。
 */
@Configuration
@Slf4j
public class RabbitMQConfig {

    /** 延迟交换机：接收下单成功后的延迟消息 */
    public static final String DELAY_EXCHANGE = "order.delay.exchange";
    /** TTL 队列：消息在此等待 TTL 到期，不设消费者 */
    public static final String DELAY_QUEUE = "order.delay.queue";
    public static final String DELAY_ROUTING_KEY = "order.delay";
    /** 取消交换机：接收 TTL 到期后的死信 */
    public static final String CANCEL_EXCHANGE = "order.cancel.exchange";
    /** 取消队列：消费者监听此队列执行关单 */
    public static final String CANCEL_QUEUE = "order.cancel.queue";
    public static final String CANCEL_ROUTING_KEY = "order.cancel";

    /** 订单超时未支付自动取消的延迟时长（毫秒），默认 15 分钟；本地验证可临时调小 */
    @Value("${sky.order.delay-ttl:900000}")
    private long delayTtl;

    @Bean
    public DirectExchange delayExchange() {
        return ExchangeBuilder.directExchange(DELAY_EXCHANGE).durable(true).build();
    }

    /**
     * TTL 队列：消息进入后等待 TTL 到期成为死信，由死信交换机转发到取消队列。
     * 注意：x-message-ttl 是队列属性，创建后不可修改——调整 TTL 需删除队列重建（运维预案）。
     */
    @Bean
    public Queue delayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .withArgument("x-message-ttl", (int) delayTtl)
                .withArgument("x-dead-letter-exchange", CANCEL_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", CANCEL_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delayQueue()).to(delayExchange()).with(DELAY_ROUTING_KEY);
    }

    @Bean
    public DirectExchange cancelExchange() {
        return ExchangeBuilder.directExchange(CANCEL_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue cancelQueue() {
        return QueueBuilder.durable(CANCEL_QUEUE).build();
    }

    @Bean
    public Binding cancelBinding() {
        return BindingBuilder.bind(cancelQueue()).to(cancelExchange()).with(CANCEL_ROUTING_KEY);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 生产者可靠性：Confirm（是否到达交换机）+ Return（是否路由到队列）回调 */
    @PostConstruct
    public void initCallbacks() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!Boolean.TRUE.equals(ack)) {
                // 生产环境应在此写补偿表并重发；当前记录日志便于排查
                log.error("消息未到达交换机，cause={}", cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned ->
                log.error("消息未路由到队列：exchange={}, routingKey={}, replyText={}, message={}",
                        returned.getExchange(), returned.getRoutingKey(),
                        returned.getReplyText(), new String(returned.getMessage().getBody())));
    }
}
