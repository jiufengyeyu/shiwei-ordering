package com.sky.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户持券视图（二次开发任务4）：持券信息 + 券模板关键信息联表
 */
@Data
public class CouponUserVO {

    private Long id;
    private Long couponId;
    private Long userId;
    /** 1未使用 2已使用 3已过期 */
    private Integer status;
    /** 券名称 */
    private String name;
    /** 券类型 */
    private Integer type;
    /** 优惠金额/折扣率 */
    private BigDecimal discountValue;
    /** 使用门槛 */
    private BigDecimal thresholdAmount;
    private LocalDateTime receiveTime;
    private LocalDateTime useTime;
    /** 使用截止时间（动态判断是否过期） */
    private LocalDateTime endTime;
}
