package com.eashare.service.impl;

import com.eashare.dto.Result;
import com.eashare.entity.VoucherOrder;
import com.eashare.mapper.VoucherOrderMapper;
import com.eashare.service.ISeckillVoucherService;
import com.eashare.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eashare.utils.RedisIdWorker;
import com.eashare.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;

import static com.eashare.utils.RedisConstants.SECKILL_RESULT_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RabbitTemplate rabbitTemplate;

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户id
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //尝试获取锁
        boolean islock = lock.tryLock();
        if (!islock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            //拿到当前代理对象，保证spring的事务能被代理对象执行（而不是this关键字拿到的当前VoucherOrderServiceImpl对象）
            //避免没提交事务就释放锁
            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
            currentProxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

    }

    @Override
    public Result querySeckillResult(Long voucherId) {
        // 获取当前用户ID
        Long userId = UserHolder.getUser().getId();
        
        // 查询Redis中的秒杀结果
        String resultKey = SECKILL_RESULT_KEY + voucherId + ":" + userId;
        String result = stringRedisTemplate.opsForValue().get(resultKey);
        
        if (result == null) {
            // 结果为空，说明还在处理中
            return Result.ok("PROCESSING");
        }
        
        // 返回结果
        if ("SUCCESS".equals(result)) {
            return Result.ok("SUCCESS");
        } else if ("FAIL".equals(result)) {
            return Result.fail("秒杀失败");
        } else {
            return Result.ok("PROCESSING");
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.校验无效请求ID
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("无效的优惠券ID");
        }
        
        // 2.获取用户id
        Long userId = UserHolder.getUser().getId();
        
        // 3.获取订单id
        Long orderId = redisIdWorker.nextId("order");
        
        // 4.执行lua脚本（传递重试次数0）
        Long r = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString(),
                "0"  // 首次调用，重试次数为0
        );
        
        // 5.判断结果是否0
        if (r != 0) {
            // 5.1 判断结果不为0，代表没有购买资格
            String errorMsg;
            switch (r.intValue()) {
                case 1:
                    errorMsg = "库存不足";
                    break;
                case 2:
                    errorMsg = "不能重复下单";
                    break;
                case 4:
                    errorMsg = "秒杀活动不存在";
                    break;
                case 5:
                    errorMsg = "不在秒杀活动时间内";
                    break;
                default:
                    errorMsg = "秒杀失败";
            }
            return Result.fail(errorMsg);
        }
        
        // 6.Lua脚本已将消息写入Redis待发队列，由后台线程可靠投递到RabbitMQ
        log.info("秒杀请求受理成功，userId: {}, voucherId: {}, orderId: {}", userId, voucherId, orderId);
        
        return Result.ok(orderId);
    }
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long r = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userId.toString()
        );
        //2.判断结果是否0
        if (r != 0) {
            //2.1 判断结果不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2 判断结果为0，代表有购买资格，把下单信息保存到阻塞队列
        VoucherOrder vO = new VoucherOrder();
        vO.setUserId(userId);
        vO.setVoucherId(voucherId);
        vO.setId(redisIdWorker.nextId("order"));
        //2.6.放入阻塞队列
        orderTasks.add(vO);

        //3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(vO.getId());
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder vO) {
        //5.一人一单
        Long userId = vO.getUserId();
        //5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", vO.getVoucherId()).count();
        //5.2判断订单是否存在
        if (count > 0) {
            log.error("用户已经购买过一次");
            return;
        }
        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", vO.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }
        //7.创建订单
        save(vO);
    }
}
