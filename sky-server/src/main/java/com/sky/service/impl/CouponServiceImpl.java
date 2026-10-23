package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.dto.CouponDTO;
import com.sky.entity.Coupon;
import com.sky.entity.CouponUser;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.CouponUserMapper;
import com.sky.result.PageResult;
import com.sky.service.CouponService;
import com.sky.vo.CouponUserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 优惠券业务实现（二次开发任务4）
 *
 * 防超发设计：Redis Lua 脚本原子"检查-扣减"库存，扣减失败（库存不足）直接拒绝；
 * 扣减成功才落库（insert 持券记录 + coupon.remain_count 同步扣减）。
 * 重复领取由 (coupon_id, user_id) 唯一索引兜底，冲突时把预扣的 Redis 库存加回。
 */
@Service
@Slf4j
public class CouponServiceImpl implements CouponService {

    /** Redis 库存 key 前缀 */
    private static final String STOCK_KEY_PREFIX = "coupon:stock:";

    /**
     * 领券原子扣减脚本：check-then-decr 必须在 Lua 内完成，拆成两条命令在并发下会超发
     * 返回值：>0 = 扣减成功（返回扣减后的剩余量）；-1 = 库存不足
     */
    private static final DefaultRedisScript<Long> DEDUCT_STOCK_SCRIPT = new DefaultRedisScript<>(
            "local stock = tonumber(redis.call('get', KEYS[1])) " +
            "if stock == nil then return -1 end " +
            "if stock <= 0 then return -1 end " +
            "redis.call('decr', KEYS[1]) " +
            "return stock - 1", Long.class);

    @Autowired
    private CouponMapper couponMapper;
    @Autowired
    private CouponUserMapper couponUserMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public PageResult pageQuery(int page, int pageSize, String name) {
        PageHelper.startPage(page, pageSize);
        Page<Coupon> couponPage = couponMapper.pageQuery(name);
        return new PageResult(couponPage.getTotal(), couponPage.getResult());
    }

    @Override
    public void add(CouponDTO couponDTO) {
        Coupon coupon = new Coupon();
        BeanUtils.copyProperties(couponDTO, coupon);
        coupon.setStatus(Coupon.UNISSUED);
        coupon.setRemainCount(couponDTO.getTotalCount());
        coupon.setCreateTime(LocalDateTime.now());
        coupon.setUpdateTime(LocalDateTime.now());
        couponMapper.insert(coupon);
        log.info("新增优惠券模板，id={}, name={}, 总量={}", coupon.getId(), coupon.getName(), coupon.getTotalCount());
    }

    @Override
    public void start(Long id) {
        Coupon coupon = couponMapper.getById(id);
        if (coupon == null) {
            throw new CouponBusinessException("优惠券不存在");
        }
        if (!Coupon.UNISSUED.equals(coupon.getStatus())) {
            throw new CouponBusinessException("只有未发放状态的券才能开始发放");
        }
        couponMapper.updateStatus(id, Coupon.ISSUING);
        // 库存写入 Redis：发放开始时一次性预热，领券时全部走 Lua 原子扣减
        stringRedisTemplate.opsForValue().set(STOCK_KEY_PREFIX + id, String.valueOf(coupon.getTotalCount()));
        log.info("优惠券开始发放，id={}, Redis 库存预热={}", id, coupon.getTotalCount());
    }

    @Override
    public void end(Long id) {
        couponMapper.updateStatus(id, Coupon.FINISHED);
    }

    @Override
    public List<Coupon> listIssuing() {
        return couponMapper.listIssuing();
    }

    @Override
    public void receive(Long couponId, Long userId) {
        Coupon coupon = couponMapper.getById(couponId);
        if (coupon == null) {
            throw new CouponBusinessException("优惠券不存在");
        }
        if (!Coupon.ISSUING.equals(coupon.getStatus())) {
            throw new CouponBusinessException("该券当前不可领取");
        }
        if (coupon.getEndTime() != null && LocalDateTime.now().isAfter(coupon.getEndTime())) {
            throw new CouponBusinessException("该券已过使用截止时间");
        }

        // 重复领取快速判断（并发场景由唯一索引兜底，这里只是优化体验）
        if (couponUserMapper.getByCouponAndUser(couponId, userId) != null) {
            throw new CouponBusinessException("您已领取过该优惠券");
        }

        // ① Lua 原子扣减 Redis 库存（防超发核心：check-then-decr 原子执行）
        Long remain = stringRedisTemplate.execute(
                DEDUCT_STOCK_SCRIPT, Collections.singletonList(STOCK_KEY_PREFIX + couponId));
        if (remain == null || remain < 0) {
            throw new CouponBusinessException("手慢了，该券已被抢完");
        }

        // ② 落库：唯一索引 (coupon_id, user_id) 防重复领取；冲突时把预扣库存加回
        try {
            CouponUser cu = CouponUser.builder()
                    .couponId(couponId)
                    .userId(userId)
                    .status(CouponUser.UNUSED)
                    .receiveTime(LocalDateTime.now())
                    .build();
            couponUserMapper.insert(cu);

            // ③ 数据库库存同步扣减（行数=0 属异常不一致，记日志告警）
            int rows = couponMapper.deductRemain(couponId);
            if (rows == 0) {
                log.error("数据库库存扣减失败（Redis/DB 库存不一致，需人工核查），couponId={}, userId={}", couponId, userId);
            }
            log.info("领券成功，couponId={}, userId={}, 扣减后 Redis 剩余={}", couponId, userId, remain);
        } catch (DuplicateKeyException e) {
            // 重复领取：把预扣的 Redis 库存加回去，保证不白扣
            stringRedisTemplate.opsForValue().increment(STOCK_KEY_PREFIX + couponId);
            throw new CouponBusinessException("您已领取过该优惠券");
        }
    }

    @Override
    public List<CouponUserVO> mine(Long userId) {
        return couponUserMapper.listByUser(userId);
    }

    @Override
    public void use(Long couponUserId, Long userId, Long orderId) {
        CouponUser cu = couponUserMapper.getById(couponUserId);
        if (cu == null) {
            throw new CouponBusinessException("优惠券不存在");
        }
        if (!cu.getUserId().equals(userId)) {
            throw new CouponBusinessException("无权使用他人的优惠券");
        }
        Coupon coupon = couponMapper.getById(cu.getCouponId());
        if (coupon != null && coupon.getEndTime() != null && LocalDateTime.now().isAfter(coupon.getEndTime())) {
            throw new CouponBusinessException("该券已过使用截止时间");
        }
        // updateUse 带 status=1 条件：行数=0 即已使用/不存在（幂等）
        int rows = couponUserMapper.updateUse(couponUserId, orderId);
        if (rows == 0) {
            throw new CouponBusinessException("该券已使用或不可用");
        }
        log.info("优惠券核销成功，couponUserId={}, userId={}, orderId={}", couponUserId, userId, orderId);
    }
}
