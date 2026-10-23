package com.sky.controller.user;

import com.sky.context.BaseContext;
import com.sky.entity.Coupon;
import com.sky.result.Result;
import com.sky.service.CouponService;
import com.sky.vo.CouponUserVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户端-优惠券接口（二次开发任务4）
 */
@RestController("userCouponController")
@RequestMapping("/user/coupon")
@Slf4j
@Api(tags = "C端-优惠券接口")
public class CouponController {

    @Autowired
    private CouponService couponService;

    @GetMapping("/list")
    @ApiOperation("可领取的优惠券列表")
    public Result<List<Coupon>> list() {
        return Result.success(couponService.listIssuing());
    }

    @PostMapping("/receive/{couponId}")
    @ApiOperation("领取优惠券（Lua 原子扣减防超发）")
    public Result receive(@PathVariable Long couponId) {
        Long userId = BaseContext.getCurrentId();
        couponService.receive(couponId, userId);
        return Result.success();
    }

    @GetMapping("/mine")
    @ApiOperation("我的优惠券")
    public Result<List<CouponUserVO>> mine() {
        Long userId = BaseContext.getCurrentId();
        return Result.success(couponService.mine(userId));
    }

    @PutMapping("/use/{couponUserId}")
    @ApiOperation("核销优惠券")
    public Result use(@PathVariable Long couponUserId, @RequestParam Long orderId) {
        Long userId = BaseContext.getCurrentId();
        couponService.use(couponUserId, userId, orderId);
        return Result.success();
    }
}
