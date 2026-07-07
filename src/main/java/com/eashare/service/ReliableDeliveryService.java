package com.eashare.service;

import cn.hutool.json.JSONUtil;
import com.eashare.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.Map;

import static com.eashare.config.RabbitMQConfig.*;
import static com.eashare.utils.RedisConstants.*;

/**
 * 可靠投递服务 - 从Redis待发队列取消息发送到RabbitMQ
 */
@Slf4j
@Service
public class ReliableDeliveryService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    @Resource
    private RabbitTemplate rabbitTemplate;

    private static final int MAX_RETRY_COUNT = 3;
    private static final long DELIVERY_INTERVAL = 100; // 每次取消息间隔100ms

    /**
     * 回滚Lua脚本
     */
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 应用启动时开启后台线程
     */
    @PostConstruct
    public void startDeliveryThread() {
        Thread deliveryThread = new Thread(this::deliveryMessages, "reliable-delivery-thread");
        deliveryThread.setDaemon(true);
        deliveryThread.start();
        log.info("可靠投递后台线程已启动");
    }

    /**
     * 持续从Redis待发队列取消息发送到RabbitMQ
     */
    private void deliveryMessages() {
        while (true) {
            try {
                // 从队列左端取消息（阻塞式，超时1秒）
                String message = stringRedisTemplate.opsForList().leftPop(SECKILL_PENDING_QUEUE);
                
                if (message == null) {
                    // 队列为空，稍后重试
                    Thread.sleep(DELIVERY_INTERVAL);
                    continue;
                }

                log.info("从待发队列取出消息: {}", message);
                
                // 解析消息
                Map<String, Object> messageMap = JSONUtil.toBean(message, Map.class);
                Long userId = Long.valueOf(messageMap.get("userId").toString());
                Long voucherId = Long.valueOf(messageMap.get("voucherId").toString());
                Long orderId = Long.valueOf(messageMap.get("id").toString());
                Integer retryCount = Integer.valueOf(messageMap.get("retryCount").toString());

                // 构建VoucherOrder对象
                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(orderId);
                voucherOrder.setUserId(userId);
                voucherOrder.setVoucherId(voucherId);

                // 发送消息到RabbitMQ
                boolean success = sendMessageToMQ(messageMap, retryCount);
                
                if (!success) {
                    // 发送失败且超过最大重试次数，回滚Redis数据
                    if (retryCount >= MAX_RETRY_COUNT) {
                        log.error("消息发送失败超过{}次，执行回滚，orderId: {}", MAX_RETRY_COUNT, orderId);
                        rollbackRedisData(voucherId, userId);
                        // 写入秒杀结果为失败
                        setResult(orderId, voucherId, userId, "FAIL");
                    } else {
                        // 未超过重试次数，重新放入队列右端，为正常消息让步
                        messageMap.put("retryCount", retryCount + 1);
                        String updatedMessage = JSONUtil.toJsonStr(messageMap);
                        stringRedisTemplate.opsForList().rightPush(SECKILL_PENDING_QUEUE, updatedMessage);
                        log.warn("消息发送失败，重新入队，retryCount: {}, orderId: {}", retryCount + 1, orderId);
                    }
                } else {
                    log.info("消息发送成功，orderId: {}", orderId);
                }

            } catch (Exception e) {
                log.error("可靠投递异常", e);
                try {
                    Thread.sleep(DELIVERY_INTERVAL);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * 发送消息到RabbitMQ
     */
    private boolean sendMessageToMQ(Map<String, Object> message, int retryCount) {
        try {
            rabbitTemplate.convertAndSend(SECKILL_EXCHANGE, SECKILL_ROUTING_KEY, message);
            return true;
        } catch (Exception e) {
            log.error("发送消息到RabbitMQ失败，retryCount: {}", retryCount, e);
            return false;
        }
    }

    /**
     * 回滚Redis中的库存和用户ID
     */
    private void rollbackRedisData(Long voucherId, Long userId) {
        try {
            Long result = stringRedisTemplate.execute(
                ROLLBACK_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
            );
            log.info("回滚Redis数据完成，voucherId: {}, userId: {}, result: {}", voucherId, userId, result);
        } catch (Exception e) {
            log.error("回滚Redis数据失败，voucherId: {}, userId: {}", voucherId, userId, e);
        }
    }

    /**
     * 设置秒杀结果
     */
    private void setResult(Long orderId, Long voucherId, Long userId, String result) {
        try {
            String resultKey = SECKILL_RESULT_KEY + voucherId + ":" + userId;
            stringRedisTemplate.opsForValue().set(resultKey, result, 300, java.util.concurrent.TimeUnit.SECONDS);
            log.info("设置秒杀结果: {}, orderId: {}", result, orderId);
        } catch (Exception e) {
            log.error("设置秒杀结果失败", e);
        }
    }
}
