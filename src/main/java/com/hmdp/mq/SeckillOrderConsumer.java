package com.hmdp.mq;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.hmdp.config.RabbitMQConfig.*;
import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class SeckillOrderConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RabbitTemplate rabbitTemplate;

    private static final String MQ_HISTORY_KEY = "mq:history:seckill:";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_RETRY_COUNT = 3;

    /**
     * 回滚Lua脚本
     */
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @RabbitListener(queues = SECKILL_QUEUE)
    public void handleSeckillOrder(Map<String, Object> message, Channel channel, Message msg) {
        long startTime = System.currentTimeMillis();
        String messageId = msg.getMessageProperties().getMessageId();
        
        Long userId = null;
        Long voucherId = null;
        Long orderId = null;
        Integer retryCount = 0;
        
        try {
            log.info("========== 收到秒杀订单消息 ==========");
            log.info("消息ID: {}", messageId);
            log.info("消息内容: {}", JSONUtil.toJsonStr(message));
            log.info("接收时间: {}", LocalDateTime.now().format(FORMATTER));
            
            // 解析消息
            userId = Long.valueOf(message.get("userId").toString());
            voucherId = Long.valueOf(message.get("voucherId").toString());
            orderId = Long.valueOf(message.get("id").toString());
            retryCount = Integer.valueOf(message.get("retryCount").toString());
            
            VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(message, new VoucherOrder(), true);
            
            // 1. 获取分布式锁（用户id+秒杀活动id）
            String lockKey = "lock:seckill:" + userId + ":" + voucherId;
            RLock lock = redissonClient.getLock(lockKey);
            boolean isLocked = lock.tryLock();
            
            if (!isLocked) {
                log.warn("获取分布式锁失败，可能正在处理相同订单，orderId: {}", orderId);
                channel.basicAck(msg.getMessageProperties().getDeliveryTag(), false);
                return;
            }
            
            try {
                // 2. 幂等性检查1：确保当前订单表不存在对应订单
                int orderCount = voucherOrderService.query()
                    .eq("id", orderId)
                    .count();
                if (orderCount > 0) {
                    log.info("订单已存在，重复消费，orderId: {}", orderId);
                    channel.basicAck(msg.getMessageProperties().getDeliveryTag(), false);
                    return;
                }
                
                // 3. 幂等性检查2：检查秒杀活动对应的用户Set中包含当前用户ID
                String orderKey = SECKILL_ORDER_KEY + voucherId;
                Boolean isMember = stringRedisTemplate.opsForSet().isMember(orderKey, userId.toString());
                if (isMember == null || !isMember) {
                    log.warn("用户不在已购买Set中，可能是已回滚的消息，orderId: {}", orderId);
                    // 回滚Redis数据
                    rollbackRedisData(voucherId, userId);
                    setResult(orderId, voucherId, userId, "FAIL");
                    channel.basicAck(msg.getMessageProperties().getDeliveryTag(), false);
                    return;
                }
                
                // 4. 执行下单逻辑
                voucherOrderService.handleVoucherOrder(voucherOrder);
                
                // 5. 成功后写入Redis成功结果
                setResult(orderId, voucherId, userId, "SUCCESS");
                
                // ACK确认
                channel.basicAck(msg.getMessageProperties().getDeliveryTag(), false);
                
                long processingTime = System.currentTimeMillis() - startTime;
                
                // 记录成功历史到Redis
                recordSuccessHistory(messageId, voucherOrder, processingTime);
                
                log.info("订单处理成功 - 订单ID: {}, 耗时: {}ms", voucherOrder.getId(), processingTime);
                log.info("========================================");
                
            } finally {
                lock.unlock();
            }
            
        } catch (Exception e) {
            long processingTime = System.currentTimeMillis() - startTime;
            
            log.error("========== 处理秒杀订单失败 ==========");
            log.error("消息ID: {}", messageId);
            log.error("错误信息: {}", e.getMessage());
            log.error("耗时: {}ms", processingTime);
            log.error("堆栈信息:", e);
            
            // 记录失败历史到Redis
            recordFailureHistory(messageId, message, e, processingTime);
            
            try {
                // 重试超过三次，回滚Redis数据并发送到死信队列
                if (retryCount >= MAX_RETRY_COUNT) {
                    log.error("重试次数超过{}次，执行回滚并发送死信，orderId: {}", MAX_RETRY_COUNT, orderId);
                    
                    // 回滚Redis数据
                    if (userId != null && voucherId != null) {
                        rollbackRedisData(voucherId, userId);
                        setResult(orderId, voucherId, userId, "FAIL");
                    }
                    
                    // 发送到死信队列
                    sendToDeadLetterQueue(message);
                    
                    // ACK确认，不再重试
                    channel.basicAck(msg.getMessageProperties().getDeliveryTag(), false);
                } else {
                    // 拒绝消息并重新入队
                    channel.basicNack(msg.getMessageProperties().getDeliveryTag(), false, true);
                    log.info("消息已重新入队，retryCount: {}", retryCount + 1);
                }
            } catch (IOException ioException) {
                log.error("消息拒绝失败", ioException);
            }
            log.error("========================================");
        }
    }

    /**
     * 记录成功的消息历史到Redis
     */
    private void recordSuccessHistory(String messageId, VoucherOrder voucherOrder, long processingTime) {
        try {
            String key = MQ_HISTORY_KEY + "success";
            String historyKey = MQ_HISTORY_KEY + voucherOrder.getId();
            
            // 保存详细历史记录（带过期时间7天）
            Map<String, Object> history = new HashMap<>();
            history.put("messageId", messageId != null ? messageId : "unknown");
            history.put("orderId", voucherOrder.getId());
            history.put("userId", voucherOrder.getUserId());
            history.put("voucherId", voucherOrder.getVoucherId());
            history.put("status", "SUCCESS");
            history.put("processingTime", processingTime + "ms");
            history.put("createTime", LocalDateTime.now().format(FORMATTER));
            
            // 保存到Hash结构，7天后过期
            stringRedisTemplate.opsForHash().putAll(historyKey, 
                history.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().toString(),
                        e -> e.getValue().toString()
                    ))
            );
            stringRedisTemplate.expire(historyKey, 7, java.util.concurrent.TimeUnit.DAYS);
            
            // 添加到成功列表（保留最近1000条）
            String summary = String.format("订单:%d | 用户:%d | 耗时:%dms | 时间:%s",
                voucherOrder.getId(), voucherOrder.getUserId(), processingTime, 
                LocalDateTime.now().format(FORMATTER));
            
            Long size = stringRedisTemplate.opsForList().rightPush(key, summary);
            if (size != null && size > 1000) {
                stringRedisTemplate.opsForList().trim(key, -1000, -1);
            }
            stringRedisTemplate.expire(key, 7, java.util.concurrent.TimeUnit.DAYS);
            
        } catch (Exception e) {
            log.error("记录成功历史失败", e);
        }
    }

    /**
     * 记录失败的消息历史到Redis
     */
    private void recordFailureHistory(String messageId, Map<String, Object> message, Exception e, long processingTime) {
        try {
            String key = MQ_HISTORY_KEY + "failure";
            String historyKey = MQ_HISTORY_KEY + "fail:" + System.currentTimeMillis();
            
            // 保存详细失败记录
            Map<String, Object> history = new HashMap<>();
            history.put("messageId", messageId != null ? messageId : "unknown");
            history.put("messageContent", JSONUtil.toJsonStr(message));
            history.put("status", "FAILURE");
            history.put("errorMessage", e.getMessage());
            history.put("processingTime", processingTime + "ms");
            history.put("createTime", LocalDateTime.now().format(FORMATTER));
            
            // 保存到Hash结构，30天后过期（失败记录保留更久）
            stringRedisTemplate.opsForHash().putAll(historyKey,
                history.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                        e2 -> e2.getKey().toString(),
                        e2 -> e2.getValue().toString()
                    ))
            );
            stringRedisTemplate.expire(historyKey, 30, java.util.concurrent.TimeUnit.DAYS);
            
            // 添加到失败列表（保留最近500条）
            String summary = String.format("失败 | 消息ID:%s | 错误:%s | 时间:%s",
                messageId, e.getMessage(), LocalDateTime.now().format(FORMATTER));
            
            Long size = stringRedisTemplate.opsForList().rightPush(key, summary);
            if (size != null && size > 500) {
                stringRedisTemplate.opsForList().trim(key, -500, -1);
            }
            stringRedisTemplate.expire(key, 30, java.util.concurrent.TimeUnit.DAYS);
            
        } catch (Exception ex) {
            log.error("记录失败历史失败", ex);
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

    /**
     * 发送消息到死信队列
     */
    private void sendToDeadLetterQueue(Map<String, Object> message) {
        try {
            rabbitTemplate.convertAndSend(SECKILL_DLX_EXCHANGE, SECKILL_DLX_ROUTING_KEY, message);
            log.info("消息已发送到死信队列: {}", JSONUtil.toJsonStr(message));
        } catch (Exception e) {
            log.error("发送死信队列失败", e);
        }
    }
}
