# 苍穹外卖项目备忘

## 项目概况
- 黑马程序员教学项目「苍穹外卖」，Spring Boot 2.7.3 多模块（sky-common / sky-pojo / sky-server）
- 功能已覆盖课程全部内容（管理端 + 用户端），共 144 个 Java 源文件
- dev 配置文件中含明文 OSS/微信/数据库密钥，已提醒用户有泄露风险

## 开发环境现状（2026-10-22 完成）
- 已就绪：JDK 21.0.10（JAVA_HOME 已配）、Maven 3.9.16、MySQL 本机 3306（root/root，sky_take_out 库及 11 张表齐全）
- Erlang 26.2.5：已安装至 D:\Erlang\erl26.2.5（未加入 PATH）
- RabbitMQ 3.13.7：已安装至 D:\Rabbitmq\RabbitMQ Server\rabbitmq_server-3.13.7，Windows 服务运行中，管理插件已启用，5672/15672 均验证通过（15672 HTTP 200，账号 guest/guest 仅限本地登录）；sbin 下 CLI 工具运行需先 set ERLANG_HOME=D:\Erlang\erl26.2.5
- JMeter 5.6.3：D:\Jmeter\apache-jmeter-5.6.3，双击 bin\jmeter.bat 可用（启动时黑窗口是宿主进程不能关）；桌面快捷方式因安全策略未自动创建，需用户右键 jmeter.bat → 发送到 → 桌面快捷方式
- Redis：待用户开启课程虚拟机 192.168.100.128:6379（单机、密码 123456、不搭集群），开好后需验证连通性
- 网络备注：GitHub 直连不稳定需重试或 8 段并行下载；华为云 mirrors 的 rabbitmq/erlang 路径返回 SPA 空壳页不可用；dlcdn.apache.org 被墙，JMeter 用清华源；erlang.org 会截断大文件，不可用
- SB3 升级注意：jjwt 0.9.1 在 JDK 21 运行时会因缺 JAXB 报错，需换 0.12.x；spring.redis → spring.data.redis；javax → jakarta。IDEA 版本存疑：D:\IntelliJ IDEA 2019.3.3 是空壳目录（无 idea64.exe），D:\Jetbrains 为空，常见位置未找到可用 IDEA 安装；用户自称用的是最新版，待用户 Help→About 确认版本号

## 用户目标
- 对项目做二次开发，写在简历上求职（2026-10-22 明确）
- **2026-10-23 全部完成**：任务1 缓存治理（QPS 4.7 倍实测）、任务2 RabbitMQ 延迟关单、任务3 Redisson+幂等、任务4 优惠券模块全部开发验证并分任务 Git 提交（f33511f/f3e618a/fa87714/9962ba6/83ea005）；简历文案定稿于简历项目描述-二次开发版.md；开发过程与面试 Q&A 全记录于二次开发实录.md
- 协作偏好：用户倾向亲自操作（压测、改配置自己来），助手给操作指引和解读而非代劳

## 压测数据（正式对照基准 = 600 并发 CLI 实测，用户 2026-10-23 亲手跑）
- **二次开发前正式基准**（524 条菜品，600 并发/60s 爬坡/120s 施压，CLI）：login 1823/s avg 123ms P99 208 max 343；dish 1820/s avg 124ms P99 212 max 362，零错误；结果文件 E:\jmeterdata\highload600-cli.jtl
- 二开完成后复测命令（jmx 内参数已固定 600/60/120，无需 -J）：java -jar D:\Jmeter\apache-jmeter-5.6.3\bin\ApacheJMeter.jar -n -t stress-test\baseline-plan.jmx -l stress-test\after-dev-result.jtl；对比前提：菜品保持 524 条、服务组合不变
- 拐点曲线（CLI 口径）：300 并发 3555/s avg 32（24 条菜品时）；600 并发 1820/s avg 124；1000 并发 1888/s avg 199——天花板 ~3700-3800 QPS（单机无缓存），600→1000 并发 QPS 仅 +4% 但延迟 +60%
- 附：300 并发 24 条菜品基线（2026-10-22 CLI）：3557/3555 QPS，avg 31/32，P99 247/252
- 压测方法论（已写入报告）：并发>300 用 CLI -n 模式；View Results Tree 不边压边开；GUI 数字仅学习用

## 二次开发方向（用户已确认范围，2026-10-22）
- 确定做：Redis 缓存治理（穿透/击穿/雪崩 + 多级缓存）、RabbitMQ 延迟消息替代定时任务关单、Redisson 分布式锁 + 接口幂等（防重复下单/支付回调）、优惠券营销模块
- 2026-10-22 更新：Spring Boot 3 升级被用户否决（认为性价比低），保持 SB 2.7.3 + JDK 21 运行，简历技术栈已同步改为 2.7；IDEA 版本问题随之无关紧要
- 压测（JMeter）：用户愿意在"足够简单"的前提下加入
- 明确不做：Docker/CI-CD 部署、AI 智能客服、微服务（Gateway/Seata 等）
- 原则：简历上不写"苍穹外卖"原名，改名换业务背景；每个改造点准备"背景-方案-数据"面试故事
- 已产出可直接复制的简历项目描述文件：简历项目描述-二次开发版.md（工作区根目录）
