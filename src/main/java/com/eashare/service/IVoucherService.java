package com.eashare.service;

import com.eashare.dto.Result;
import com.eashare.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *

 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);

    /**
     * 预热秒杀库存到Redis
     */
    void warmUpSeckillStock();
}
