package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户持券（二次开发任务4）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponUser {

    /** 状态：未使用 */
    public static final Integer UNUSED = 1;
    /** 状态：已使用 */
    public static final Integer USED = 2;
    /** 状态：已过期 */
    public static final Integer EXPIRED = 3;

    private Long id;
    private Long couponId;
    private Long userId;
    private Integer status;
    private LocalDateTime receiveTime;
    private LocalDateTime useTime;
    /** 核销时关联的订单 id */
    private Long orderId;
}
