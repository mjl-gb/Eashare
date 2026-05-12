package com.hmdp.mq;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static com.hmdp.config.RabbitMQConfig.SECKILL_QUEUE;

@Slf4j
@Component
public class SeckillOrderConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String MQ_HISTORY_KEY = "mq:history:seckill:";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @RabbitListener(queues = SECKILL_QUEUE)
    public void handleSeckillOrder(Map<String, Object> message, Channel channel, Message msg) {
        long startTime = System.currentTimeMillis();
        String messageId = msg.getMessageProperties().getMessageId();
        
        try {
            log.info("========== 收到秒杀订单消息 ==========");
            log.info("消息ID: {}", messageId);
            log.info("消息内容: {}", JSONUtil.toJsonStr(message));
            log.info("接收时间: {}", LocalDateTime.now().format(FORMATTER));
            
            VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(message, new VoucherOrder(), true);
            
            // 处理订单
            voucherOrderService.handleVoucherOrder(voucherOrder);
            
            // ACK确认
            channel.basicAck(msg.getMessageProperties().getDeliveryTag(), false);
            
            long processingTime = System.currentTimeMillis() - startTime;
            
            // 记录成功历史到Redis
            recordSuccessHistory(messageId, voucherOrder, processingTime);
            
            log.info("订单处理成功 - 订单ID: {}, 耗时: {}ms", voucherOrder.getId(), processingTime);
            log.info("========================================");
            
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
                // 拒绝消息并重新入队
                channel.basicNack(msg.getMessageProperties().getDeliveryTag(), false, true);
                log.info("消息已重新入队");
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
}
