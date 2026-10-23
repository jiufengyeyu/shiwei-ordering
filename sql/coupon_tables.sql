-- 二次开发任务4：优惠券营销模块建表
-- 执行人：WorkBuddy；日期：2026-10-23
-- 回滚：DROP TABLE coupon_user; DROP TABLE coupon;

CREATE TABLE coupon (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(64)     NOT NULL COMMENT '券名称',
    type            INT             NOT NULL DEFAULT 1 COMMENT '券类型：1满减券 2折扣券 3无门槛券',
    discount_value  DECIMAL(10,2)   NOT NULL COMMENT '优惠金额（满减/无门槛）或折扣率（折扣券，如0.85）',
    threshold_amount DECIMAL(10,2)  NOT NULL DEFAULT 0 COMMENT '使用门槛：满 X 元可用，0=无门槛',
    total_count     INT             NOT NULL COMMENT '发放总量',
    remain_count    INT             NOT NULL DEFAULT 0 COMMENT '剩余可领数量（数据库层库存，Redis 预扣后同步扣减）',
    per_user_limit  INT             NOT NULL DEFAULT 1 COMMENT '每人限领张数（当前实现：每人限领1张，由唯一索引保证）',
    status          INT             NOT NULL DEFAULT 0 COMMENT '状态：0未发放 1发放中 2已结束',
    start_time      DATETIME        NULL COMMENT '发放开始时间',
    end_time        DATETIME        NULL COMMENT '使用截止时间（过期后券不可用）',
    create_time     DATETIME        NULL,
    update_time     DATETIME        NULL,
    KEY idx_status (status)
) COMMENT '优惠券模板';

CREATE TABLE coupon_user (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    coupon_id    BIGINT      NOT NULL COMMENT '券模板 id',
    user_id      BIGINT      NOT NULL COMMENT '用户 id',
    status       INT         NOT NULL DEFAULT 1 COMMENT '状态：1未使用 2已使用 3已过期',
    receive_time DATETIME    NULL COMMENT '领取时间',
    use_time     DATETIME    NULL COMMENT '核销时间',
    order_id     BIGINT      NULL COMMENT '核销时关联的订单 id',
    UNIQUE KEY uk_coupon_user (coupon_id, user_id) COMMENT '每人每券限领1张：并发防重的数据库兜底',
    KEY idx_user (user_id)
) COMMENT '用户持券表';
