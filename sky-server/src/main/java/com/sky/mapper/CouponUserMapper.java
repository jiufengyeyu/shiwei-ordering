package com.sky.mapper;

import com.sky.entity.CouponUser;
import com.sky.vo.CouponUserVO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CouponUserMapper {

    @Insert("insert into coupon_user (coupon_id, user_id, status, receive_time) " +
            "values (#{couponId}, #{userId}, #{status}, #{receiveTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(CouponUser couponUser);

    @Select("select * from coupon_user where id = #{id}")
    CouponUser getById(Long id);

    @Select("select * from coupon_user where coupon_id = #{couponId} and user_id = #{userId}")
    CouponUser getByCouponAndUser(@Param("couponId") Long couponId, @Param("userId") Long userId);

    /**
     * 核销：status=1 未使用 → 2 已使用，关联订单
     * @return 影响行数：0 表示券已使用/不存在（天然幂等）
     */
    @Update("update coupon_user set status = 2, use_time = now(), order_id = #{orderId} " +
            "where id = #{id} and status = 1")
    int updateUse(@Param("id") Long id, @Param("orderId") Long orderId);

    @Select("select cu.id, cu.coupon_id as couponId, cu.user_id as userId, cu.status, " +
            "cu.receive_time as receiveTime, cu.use_time as useTime, " +
            "c.name, c.type, c.discount_value as discountValue, c.threshold_amount as thresholdAmount, " +
            "c.end_time as endTime " +
            "from coupon_user cu join coupon c on cu.coupon_id = c.id " +
            "where cu.user_id = #{userId} order by cu.id desc")
    List<CouponUserVO> listByUser(Long userId);
}
