package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.entity.Coupon;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CouponMapper {

    @Insert("insert into coupon (name, type, discount_value, threshold_amount, total_count, remain_count, " +
            "per_user_limit, status, start_time, end_time, create_time, update_time) " +
            "values (#{name}, #{type}, #{discountValue}, #{thresholdAmount}, #{totalCount}, #{remainCount}, " +
            "#{perUserLimit}, #{status}, #{startTime}, #{endTime}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Coupon coupon);

    @Select("select * from coupon order by id desc")
    List<Coupon> list();

    @Select("<script>select * from coupon " +
            "<where><if test='name != null and name != \"\"'>and name like concat('%', #{name}, '%')</if></where> " +
            "order by id desc</script>")
    Page<Coupon> pageQuery(@Param("name") String name);

    @Select("select * from coupon where id = #{id}")
    Coupon getById(Long id);

    @Select("select * from coupon where status = 1 order by id desc")
    List<Coupon> listIssuing();

    @Update("update coupon set status = #{status}, update_time = now() where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 数据库层库存扣减（Redis 预扣成功后的同步落库）
     * @return 影响行数：0 表示 DB 库存已不足（Redis/DB 不一致的异常信号）
     */
    @Update("update coupon set remain_count = remain_count - 1, update_time = now() " +
            "where id = #{id} and remain_count > 0")
    int deductRemain(@Param("id") Long id);
}
