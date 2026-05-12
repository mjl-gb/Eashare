package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.hmdp.config.RabbitMQConfig.*;

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
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        Long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long r = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString()
        );
        //2.判断结果是否0
        if (r != 0) {
            //2.1 判断结果不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        
        //3.发送消息到RabbitMQ
        Map<String, Object> message = new HashMap<>();
        message.put("userId", userId);
        message.put("voucherId", voucherId);
        message.put("id", orderId);
        
        rabbitTemplate.convertAndSend(SECKILL_EXCHANGE, SECKILL_ROUTING_KEY, message);
        
        //4.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
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
