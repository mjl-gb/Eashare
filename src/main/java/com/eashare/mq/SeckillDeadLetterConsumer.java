package com.eashare.mq;

import cn.hutool.json.JSONUtil;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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
import java.util.concurrent.TimeUnit;

import static com.eashare.config.RabbitMQConfig.SECKILL_DLX_QUEUE;
import static com.eashare.utils.RedisConstants.*;

/**
 * 死信队列消费者
 * 处理超过重试次数的失败消息，记录日志并执行快速失败回滚
 */
@Slf4j
@Component
public class SeckillDeadLetterConsumer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
     * 监听死信队列
     */
    @RabbitListener(queues = SECKILL_DLX_QUEUE)
    public void handleDeadLetter(Map<String, Object> message, Channel channel, Message msg) {
        long deliveryTag = msg.getMessageProperties().getDeliveryTag();
        String messageId = msg.getMessageProperties().getMessageId();
        
        try {
            log.error("========== 收到死信消息 ==========");
            log.error("消息ID: {}", messageId);
            log.error("消息内容: {}", JSONUtil.toJsonStr(message));
            log.error("接收时间: {}", LocalDateTime.now().format(FORMATTER));
            
            // 解析消息
            Long userId = Long.valueOf(message.get("userId").toString());
            Long voucherId = Long.valueOf(message.get("voucherId").toString());
            Long orderId = Long.valueOf(message.get("id").toString());
            Integer retryCount = Integer.valueOf(message.get("retryCount").toString());
            
            log.error("订单ID: {}, 用户ID: {}, 优惠券ID: {}, 重试次数: {}", 
                orderId, userId, voucherId, retryCount);
            
            // 1. 记录死信日志到Redis
            recordDeadLetterLog(messageId, message, orderId, userId, voucherId, retryCount);
            
            // 2. 回滚Redis数据（库存和用户ID）
            rollbackRedisData(voucherId, userId);
            
            // 3. 设置秒杀结果为失败
            setResult(orderId, voucherId, userId, "FAIL");
            
            // 4. ACK确认
            channel.basicAck(deliveryTag, false);
            
            log.error("死信消息处理完成，已回滚数据并记录日志");
            log.error("========================================");
            
        } catch (Exception e) {
            log.error("========== 处理死信消息失败 ==========");
            log.error("错误信息: {}", e.getMessage(), e);
            log.error("========================================");
            
            try {
                // 拒绝消息，不重新入队（避免无限循环）
                channel.basicNack(deliveryTag, false, false);
                log.warn("死信消息已拒绝，不再重试");
            } catch (IOException ioException) {
                log.error("消息拒绝失败", ioException);
            }
        }
    }

    /**
     * 记录死信日志到Redis
     */
    private void recordDeadLetterLog(String messageId, Map<String, Object> message, 
                                      Long orderId, Long userId, Long voucherId, Integer retryCount) {
        try {
            String deadLetterKey = "seckill:dead:letter:" + orderId;
            
            // 保存死信详细信息
            Map<String, Object> deadLetterInfo = new HashMap<>();
            deadLetterInfo.put("messageId", messageId != null ? messageId : "unknown");
            deadLetterInfo.put("orderId", orderId);
            deadLetterInfo.put("userId", userId);
            deadLetterInfo.put("voucherId", voucherId);
            deadLetterInfo.put("retryCount", retryCount);
            deadLetterInfo.put("messageContent", JSONUtil.toJsonStr(message));
            deadLetterInfo.put("status", "DEAD_LETTER");
            deadLetterInfo.put("createTime", LocalDateTime.now().format(FORMATTER));
            
            // 保存到Hash结构，30天后过期
            stringRedisTemplate.opsForHash().putAll(deadLetterKey,
                deadLetterInfo.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().toString(),
                        e -> e.getValue().toString()
                    ))
            );
            stringRedisTemplate.expire(deadLetterKey, 30, java.util.concurrent.TimeUnit.DAYS);
            
            // 添加到死信列表（保留最近500条）
            String deadLetterListKey = "seckill:dead:letter:list";
            String summary = String.format("订单:%d | 用户:%d | 券:%d | 重试:%d次 | 时间:%s",
                orderId, userId, voucherId, retryCount, LocalDateTime.now().format(FORMATTER));
            
            Long size = stringRedisTemplate.opsForList().rightPush(deadLetterListKey, summary);
            if (size != null && size > 500) {
                stringRedisTemplate.opsForList().trim(deadLetterListKey, -500, -1);
            }
            stringRedisTemplate.expire(deadLetterListKey, 30, java.util.concurrent.TimeUnit.DAYS);
            
            log.info("死信日志记录成功，orderId: {}", orderId);
            
        } catch (Exception e) {
            log.error("记录死信日志失败", e);
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
            stringRedisTemplate.opsForValue().set(resultKey, result, 300, TimeUnit.SECONDS);
            log.info("设置秒杀结果: {}, orderId: {}", result, orderId);
        } catch (Exception e) {
            log.error("设置秒杀结果失败", e);
        }
    }
}
