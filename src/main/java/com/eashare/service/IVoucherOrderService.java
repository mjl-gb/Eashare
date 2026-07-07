package com.eashare.service;

import com.eashare.dto.Result;
import com.eashare.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder vO);

    void handleVoucherOrder(VoucherOrder voucherOrder);
    
    Result querySeckillResult(Long voucherId);
}
