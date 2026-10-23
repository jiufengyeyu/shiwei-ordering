package com.sky.message;

import com.rabbitmq.client.Channel;
import com.sky.config.RabbitMQConfig;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 二次开发任务2：订单超时关单消费者
 *
 * 监听取消队列（延迟 15 分钟到期后的死信），执行超时关单。
 *
 * 幂等核心：消费时校验订单状态——只有仍处于"待付款(status=1)"的订单才取消；
 * 用户已支付(status=2+)/已取消(status=6)/订单不存在的消息直接确认跳过。
 * 因此重复投递、重复消费都不会产生副作用。
 */
@Component
@Slf4j
public class OrderCancelListener {

    @Autowired
    private OrderMapper orderMapper;

    @RabbitListener(queues = RabbitMQConfig.CANCEL_QUEUE)
    public void onOrderDelayExpire(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Long orderId = null;
        try {
            orderId = Long.parseLong(new String(message.getBody()));
            log.info("收到订单超时延迟消息，orderId={}，开始处理超时关单", orderId);

            Orders order = orderMapper.getById(orderId);
            if (order == null) {
                // 订单不存在（测试消息/脏数据）：确认丢弃，避免死循环重投
                log.warn("订单不存在，跳过关单，orderId={}", orderId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 状态机校验：只有待付款订单需要超时取消（幂等：重复消费直接跳过）
            if (Orders.PENDING_PAYMENT.equals(order.getStatus())) {
                Orders update = Orders.builder()
                        .id(order.getId())
                        .status(Orders.CANCELLED)
                        .cancelReason("订单超时未支付，自动取消")
                        .cancelTime(LocalDateTime.now())
                        .build();
                orderMapper.update(update);
                log.info("订单已超时自动取消，orderId={}, orderNumber={}", orderId, order.getNumber());
            } else {
                log.info("订单状态已非待付款（当前 status={}），无需关单，orderId={}", order.getStatus(), orderId);
            }
            channel.basicAck(deliveryTag, false);
        } catch (NumberFormatException e) {
            // 消息体非法：坏消息不重回队列（避免循环重投），记日志供人工核查
            log.error("消息体非法，无法解析为订单 id，直接丢弃：{}", new String(message.getBody()));
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 处理异常：不重回队列（重回会立即循环消费），记日志人工介入
            log.error("订单超时关单处理失败，消息不重回队列（需人工核查），orderId={}", orderId, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
