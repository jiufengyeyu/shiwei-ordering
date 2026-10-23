# 拾味点餐（Shiwei Ordering）

餐饮外卖点餐平台：管理端 + 用户端双端架构，覆盖点餐 → 支付 → 订单履约的完整业务闭环。

## 技术栈

Spring Boot 2.7 · MyBatis · MySQL · Redis · RabbitMQ · Redisson · JWT · WebSocket · Knife4j

## 模块结构

| 模块 | 说明 |
|---|---|
| shiwei-ordering-common | 公共组件：工具类、常量、全局异常、JWT |
| shiwei-ordering-pojo | 实体 / DTO / VO |
| shiwei-ordering-server | 业务服务（内嵌 Tomcat，可执行 jar） |

## 核心特性

- **多级缓存**：Caffeine + Redis 两级缓存，布隆过滤器防穿透、互斥锁防击穿、TTL 随机化防雪崩；600 并发压测核心接口 QPS 8,600+（优化前 1,820），平均响应 18ms
- **订单超时自动关单**：RabbitMQ TTL + 死信队列延迟消息，下单即投递 15 分钟关单消息，替代定时任务轮询；生产者 Confirm/Return + 消费者手动 ACK + 状态机幂等
- **下单/支付三层防护**：Redisson 分布式锁（用户维度，事务提交后释放）+ 订单号唯一索引 + 支付状态机校验
- **优惠券**：Redis Lua 脚本原子扣减库存防超发，(coupon_id, user_id) 唯一索引防重复领取；1000 用户并发抢 100 张券实测零超发、零重复

## 快速开始

1. 执行 `sql/` 下建表脚本（MySQL）
2. 修改 `shiwei-ordering-server/src/main/resources/application-dev.yml` 中的数据库 / Redis 连接信息
3. 构建：`mvn -DskipTests package`
4. 启动：`java -jar shiwei-ordering-server/target/shiwei-ordering-server-1.0-SNAPSHOT.jar --server.port=8080`
5. 接口文档：http://localhost:8080/doc.html

## 压测

`stress-test/` 目录提供 JMeter 压测计划（300/600/1000 并发阶梯）与结果统计脚本：

```bash
java -jar <jmeter>/bin/ApacheJMeter.jar -n -t stress-test/baseline-plan.jmx -l result.jtl
python stress-test/stats.py result.jtl
```
