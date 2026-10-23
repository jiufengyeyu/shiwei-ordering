package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板（二次开发任务4）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Coupon {

    /** 状态：未发放 */
    public static final Integer UNISSUED = 0;
    /** 状态：发放中 */
    public static final Integer ISSUING = 1;
    /** 状态：已结束 */
    public static final Integer FINISHED = 2;

    private Long id;
    private String name;
    /** 券类型：1满减券 2折扣券 3无门槛券 */
    private Integer type;
    /** 优惠金额或折扣率 */
    private BigDecimal discountValue;
    /** 使用门槛：满 X 元可用，0=无门槛 */
    private BigDecimal thresholdAmount;
    /** 发放总量 */
    private Integer totalCount;
    /** 剩余可领数量（数据库层库存） */
    private Integer remainCount;
    /** 每人限领张数 */
    private Integer perUserLimit;
    private Integer status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
