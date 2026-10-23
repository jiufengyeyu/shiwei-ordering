package com.sky.service;

import com.sky.dto.CouponDTO;
import com.sky.entity.Coupon;
import com.sky.result.PageResult;
import com.sky.vo.CouponUserVO;

import java.util.List;

public interface CouponService {

    /** 管理端分页查询 */
    PageResult pageQuery(int page, int pageSize, String name);

    /** 管理端新增券模板（未发放状态） */
    void add(CouponDTO couponDTO);

    /** 管理端开始发放：状态置为发放中，并把库存写入 Redis */
    void start(Long id);

    /** 管理端结束发放 */
    void end(Long id);

    /** 用户端：可领取的优惠券列表 */
    List<Coupon> listIssuing();

    /** 用户端领券（Redis Lua 原子扣减防超发 + 唯一索引防重领） */
    void receive(Long couponId, Long userId);

    /** 用户端：我的优惠券 */
    List<CouponUserVO> mine(Long userId);

    /** 用户端：核销优惠券（关联订单） */
    void use(Long couponUserId, Long userId, Long orderId);
}
