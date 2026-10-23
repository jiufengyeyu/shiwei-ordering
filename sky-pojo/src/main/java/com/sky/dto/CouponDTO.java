package com.sky.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板新增/编辑 DTO（二次开发任务4）
 */
@Data
public class CouponDTO {

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
    /** 每人限领张数 */
    private Integer perUserLimit;
    /** 使用截止时间 */
    private LocalDateTime endTime;
}
