package com.eashare.controller;


import com.eashare.dto.Result;
import com.eashare.entity.Voucher;
import com.eashare.service.IVoucherOrderService;
import com.eashare.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;
    
    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }

    /**
     * 预热秒杀库存到Redis
     * @return 结果
     */
    @PostMapping("/warmup")
    public Result warmUpSeckillStock() {
        voucherService.warmUpSeckillStock();
        return Result.ok("库存预热成功");
    }
    
    /**
     * 查询秒杀结果（前端轮询）
     * @param voucherId 优惠券ID
     * @return 秒杀结果
     */
    @GetMapping("/result/{voucherId}")
    public Result querySeckillResult(@PathVariable("voucherId") Long voucherId) {
        return voucherOrderService.querySeckillResult(voucherId);
    }
}
