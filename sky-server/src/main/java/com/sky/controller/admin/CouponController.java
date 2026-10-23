package com.sky.controller.admin;

import com.sky.dto.CouponDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.CouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端-优惠券接口（二次开发任务4）
 */
@RestController("adminCouponController")
@RequestMapping("/admin/coupon")
@Slf4j
@Api(tags = "管理端-优惠券接口")
public class CouponController {

    @Autowired
    private CouponService couponService;

    @GetMapping("/page")
    @ApiOperation("分页查询")
    public Result<PageResult> page(@RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "10") int pageSize,
                                   @RequestParam(required = false) String name) {
        return Result.success(couponService.pageQuery(page, pageSize, name));
    }

    @PostMapping
    @ApiOperation("新增优惠券模板")
    public Result add(@RequestBody CouponDTO couponDTO) {
        couponService.add(couponDTO);
        return Result.success();
    }

    @PutMapping("/start/{id}")
    @ApiOperation("开始发放（库存预热到 Redis）")
    public Result start(@PathVariable Long id) {
        couponService.start(id);
        return Result.success();
    }

    @PutMapping("/end/{id}")
    @ApiOperation("结束发放")
    public Result end(@PathVariable Long id) {
        couponService.end(id);
        return Result.success();
    }
}
