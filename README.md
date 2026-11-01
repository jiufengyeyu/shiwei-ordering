# 拾味点餐（Shiwei Ordering）

餐饮外卖点餐平台：管理端 + 用户端双端架构，覆盖点餐 → 支付 → 订单履约的完整业务闭环。

> 本项目基于开源教学项目 **sky-take-out（苍穹外卖）** 二次开发。原有的菜品 / 套餐 / 订单 / 购物车 / 员工管理骨架保留，
> 查询链路、订单履约、资金安全与营销模块为二次开发重写，具体范围见「二次开发说明」。

## 技术栈

Spring Boot 2.7 · MyBatis · MySQL · Redis · RabbitMQ · Redisson · JWT · WebSocket · Knife4j

## 模块结构

| 目录 | artifactId | 说明 |
|---|---|---|
| `sky-common` | shiwei-ordering-common | 公共组件：工具类、常量、全局异常、JWT |
| `sky-pojo` | shiwei-ordering-pojo | 实体 / DTO / VO |
| `sky-server` | shiwei-ordering-server | 业务服务（内嵌 Tomcat，可执行 jar） |

## 核心特性

- **多级缓存**：Caffeine + Redis 两级缓存，布隆过滤器防穿透、互斥锁防击穿、TTL 随机化防雪崩；600 并发压测核心接口 QPS 8,600+（优化前 1,820），平均响应 18ms
- **订单超时自动关单**：RabbitMQ TTL + 死信队列延迟消息，下单即投递 15 分钟关单消息，替代定时任务轮询；生产者 Confirm/Return + 消费者手动 ACK + 状态机幂等
- **下单/支付三层防护**：Redisson 分布式锁（用户维度，事务提交后释放）+ 订单号唯一索引 + 支付状态机校验
- **优惠券**：Redis Lua 脚本原子扣减库存防超发，(coupon_id, user_id) 唯一索引防重复领取；1000 用户并发抢 100 张券实测零超发、零重复

## 二次开发说明

原项目提供基础 CRUD 与支付对接；下表是二次开发改动的范围，每项改动对应一个独立提交，可直接查看 commit 记录：

| 方向 | 二次开发内容 |
|---|---|
| 查询性能 | 引入 Caffeine + Redis 多级缓存；针对穿透 / 击穿 / 雪崩分别实现布隆过滤器 + 空值缓存、互斥锁 + 双重检查、TTL 随机化；`listWithFlavor` 的 1+N 次查询改造为 IN 批量查（61 次往返 → 2 次） |
| 订单履约 | 退役 `processTimeoutOrder` 定时轮询，改为 RabbitMQ TTL + 死信队列延迟消息；补生产者 Confirm/Return、消费者手动 ACK 与订单状态机幂等 |
| 资金安全 | Redisson 分布式锁（锁边界包住事务边界，`afterCompletion` 后释放）+ 订单号唯一索引 `uk_number` + 支付状态机校验；修复已取消订单被迟到回调「复活」的缺陷 |
| 营销活动 | 优惠券发放 / 领取 / 核销全生命周期；Redis Lua 原子扣减库存，唯一索引 `uk_coupon_user` 防重复领取，冲突时回补 Redis 库存 |
| 连接层调优 | 压测中定位到 Redisson 自动配置替换 Lettuce 连接工厂导致吞吐腰斩，显式声明 Lettuce 连接工厂；Tomcat keep-alive 参数调整解决客户端端口耗尽 |

原项目已有、本次未改动的部分：员工 / 分类 / 菜品 / 套餐基础 CRUD、微信登录与支付对接、地址簿、购物车、WebSocket 来单提醒。

## 快速开始

1. 执行 `sql/` 下建表脚本（MySQL）
2. 复制配置模板并填入本地凭据：
   ```bash
   cp sky-server/src/main/resources/application-dev.example.yml \
      sky-server/src/main/resources/application-dev.yml
   ```
   需填写 MySQL、Redis、阿里云 OSS、微信支付四项。`application-dev.yml` 已在 `.gitignore` 中，不会入库。
3. 构建：`mvn -DskipTests package`
4. 启动：`java -jar sky-server/target/shiwei-ordering-server-1.0-SNAPSHOT.jar --server.port=8080`
5. 接口文档：http://localhost:8080/doc.html

## 压测

`stress-test/` 目录提供 JMeter 压测计划（300/600/1000 并发阶梯）与结果统计脚本：

```bash
java -jar <jmeter>/bin/ApacheJMeter.jar -n -t stress-test/baseline-plan.jmx -l result.jtl
python stress-test/stats.py result.jtl
```
