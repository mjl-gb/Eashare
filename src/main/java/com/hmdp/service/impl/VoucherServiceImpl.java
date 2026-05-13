package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ACTIVITY_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 应用启动时自动预热秒杀库存
     */
    @PostConstruct
    public void autoWarmUpSeckillStock() {
        try {
            warmUpSeckillStock();
        } catch (Exception e) {
            log.error("应用启动时预热秒杀库存失败", e);
        }
    }

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 保存秒杀库存信息到Redis
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());

    }

    @Override
    public void warmUpSeckillStock() {
        // 1.查询所有秒杀优惠券
        List<SeckillVoucher> seckillVouchers = seckillVoucherService.list();
        
        // 2.将库存和活动时间预热到Redis
        for (SeckillVoucher seckillVoucher : seckillVouchers) {
            // 预热库存
            String stockKey = SECKILL_STOCK_KEY + seckillVoucher.getVoucherId();
            stringRedisTemplate.opsForValue().set(stockKey, seckillVoucher.getStock().toString());
            
            // 预热活动信息到Hash
            String activityKey = SECKILL_ACTIVITY_KEY + seckillVoucher.getVoucherId();
            stringRedisTemplate.opsForHash().put(activityKey, "beginTime", 
                String.valueOf(seckillVoucher.getBeginTime().toEpochSecond(java.time.ZoneOffset.UTC)));
            stringRedisTemplate.opsForHash().put(activityKey, "endTime", 
                String.valueOf(seckillVoucher.getEndTime().toEpochSecond(java.time.ZoneOffset.UTC)));
            
            log.info("预热秒杀库存到Redis，voucherId: {}, stock: {}, beginTime: {}, endTime: {}", 
                seckillVoucher.getVoucherId(), seckillVoucher.getStock(), 
                seckillVoucher.getBeginTime(), seckillVoucher.getEndTime());
        }
        
        log.info("秒杀库存预热完成，共预热 {} 个秒杀券", seckillVouchers.size());
    }
}
