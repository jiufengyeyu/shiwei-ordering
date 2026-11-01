# 拾味点餐 · Shiwei Ordering

餐饮外卖点餐平台｜管理端 + 用户端双端架构｜Spring Boot + Redis + RabbitMQ

![Java](https://img.shields.io/badge/Java-8-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.3-6DB33F)
![MySQL](https://img.shields.io/badge/MySQL-5.7-4479A1)
![Redis](https://img.shields.io/badge/Redis-6.x-DC382D)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.x-FF6600)

覆盖「浏览菜品 → 加购物车 → 下单 → 支付 → 履约 → 完成」的完整业务闭环。在原教学项目的基础上，围绕**查询性能、订单履约、资金安全、营销活动**四个方向做了二次开发，每一项都有压测数据或缺陷修复作为支撑。

---

## 二次开发亮点

| 方向 | 做了什么 | 效果 |
|---|---|---|
| **多级缓存** | Caffeine（L1）+ Redis（L2）两级缓存；布隆过滤器 + 空值缓存防穿透，互斥锁 + 双重检查防击穿，TTL 随机化防雪崩；`listWithFlavor` 的 1+N 次查询改造为 IN 批量查 | 600 并发下核心接口 QPS **1,820 → 8,632（4.7 倍）**，平均响应 **124ms → 18ms**；查询往返 61 次 → 2 次 |
| **订单超时关单** | 退役 `processTimeoutOrder` 每分钟轮询，改为 RabbitMQ TTL + 死信队列延迟消息；生产者 Confirm/Return 保证投递可靠，消费者手动 ACK + 订单状态机幂等 | 关单精度从「分钟级、全表扫描」变为「精确到秒、零轮询」 |
| **下单/支付防护** | Redisson 分布式锁（用户维度，`afterCompletion` 事务提交后释放）+ 订单号唯一索引 `uk_number` + 支付状态机校验；修复已取消订单被迟到回调「复活」的资损缺陷 | 并发重复下单 0 例；回调乱序不再改变终态 |
| **优惠券** | 发放 / 领取 / 核销全生命周期；Redis Lua 脚本原子扣减库存，`(coupon_id, user_id)` 唯一索引防重复领取，冲突时回补 Redis 库存 | 1000 用户并发抢 100 张券：零超发、零重复 |
| **连接层调优** | 压测中定位到 Redisson 自动配置替换 Lettuce 连接工厂导致吞吐腰斩，改为显式声明 Lettuce 连接工厂；调整 Tomcat keep-alive 参数 | 解决压测后期的客户端端口耗尽与吞吐骤降 |

> 原项目已有、本次未改动的部分：员工 / 分类 / 菜品 / 套餐基础 CRUD、微信登录与支付对接、地址簿、购物车、WebSocket 来单提醒、每日派送状态流转。

## 功能概览

**管理端**：员工管理、分类管理、菜品与口味管理、套餐管理、订单接单/拒单/派送、营业数据统计、来单语音提醒

**用户端**：微信授权登录、菜品分类浏览、购物车、地址簿、下单支付、订单跟踪与再来一单、优惠券领取与使用

## 项目结构

```
shiwei-ordering
├── sky-common/           公共组件：工具类、常量、全局异常、JWT、上下文
├── sky-pojo/             实体 / DTO / VO
├── sky-server/           业务服务（内嵌 Tomcat，可执行 jar）
│   └── com.sky
│       ├── cache/        Caffeine 本地缓存与缓存防护组件
│       ├── config/       Redis、RabbitMQ、Redisson、WebMvc、Knife4j 配置
│       ├── controller/   管理端与用户端接口
│       ├── message/      RabbitMQ 消费者（订单超时关单）
│       ├── service/      业务实现
│       ├── mapper/       MyBatis 接口
│       └── task/         定时任务（仅保留每日派送状态流转）
├── sql/                  建表脚本（含优惠券表、菜品测试数据）
└── stress-test/          JMeter 压测计划、结果文件与统计脚本
```

## 技术选型

**框架**：Spring Boot 2.7.3 · Spring MVC · MyBatis 2.2 · PageHelper · Knife4j 3.0
**存储**：MySQL 5.7 · Redis 6.x（Spring Data Redis + Lettuce）· Caffeine
**中间件**：RabbitMQ 3.x（Spring AMQP，手动 ACK + Confirm/Return）
**其他**：Redisson（分布式锁）· JJWT 0.9 · WebSocket · 阿里云 OSS · 微信支付 v3

## 快速开始

**环境要求**：JDK 8+ · Maven 3.6+ · MySQL 5.7+ · Redis 6.x · RabbitMQ 3.x（需开启管理插件）

1. 执行 `sql/` 下的建表脚本
2. 复制配置模板并填入本地凭据：

   ```bash
   cp sky-server/src/main/resources/application-dev.example.yml \
      sky-server/src/main/resources/application-dev.yml
   ```

   需填写 MySQL、Redis、阿里云 OSS、微信支付四项。`application-dev.yml` 已在 `.gitignore` 中，不会入库。

3. 构建：

   ```bash
   mvn -DskipTests package
   ```

4. 启动：

   ```bash
   java -jar sky-server/target/shiwei-ordering-server-1.0-SNAPSHOT.jar --server.port=8080
   ```

5. 接口文档：<http://localhost:8080/doc.html>

## 压测

`stress-test/` 提供 JMeter 压测计划（300 / 600 / 1000 并发阶梯）与结果统计脚本：

```bash
java -jar <jmeter>/bin/ApacheJMeter.jar -n -t stress-test/baseline-plan.jmx -l result.jtl
python stress-test/stats.py result.jtl
```

600 并发、核心接口「菜品分页查询」三轮复测（方差 < 2%）：

| 指标 | 无缓存基线 | 多级缓存后 | 变化 |
|---|---|---|---|
| 吞吐量 QPS | 1,820 | 8,632 | **4.7 倍** |
| 平均响应 | 124 ms | 18 ms | **-85%** |
| 登录接口 QPS（未优化，外溢受益） | 1,823 | 8,634 | 4.7 倍 |

> 说明：登录接口本身未做任何改造，提速完全来自缓存释放出的数据库连接，属于旁证而非优化成果。

## 关于本项目

本项目基于开源教学项目 **sky-take-out（苍穹外卖）** 二次开发，用于个人学习与技术实践。原项目的领域模型、基础 CRUD 与第三方对接保持不变，改动范围见上方「二次开发亮点」，每一项对应一个独立提交，可直接查阅 commit 记录。

代码中的敏感配置（数据库密码、对象存储密钥、支付密钥）均已从版本库移除，仅保留 `application-dev.example.yml` 模板。

致谢：原教学项目 **sky-take-out（苍穹外卖）** · [Caffeine](https://github.com/ben-manes/caffeine) · [Redisson](https://github.com/redisson/redisson) · [Knife4j](https://doc.xiaominfo.com/)
