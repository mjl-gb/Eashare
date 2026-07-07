package com.eashare.mq;

import cn.hutool.json.JSONUtil;
import com.eashare.dto.CanalDataChangeEvent;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

import static com.eashare.config.RabbitMQConfig.CANAL_CACHE_DELETE_QUEUE;
import static com.eashare.utils.RedisConstants.*;

/**
 * Canal 缓存删除消费者
 * 监听 MySQL binlog 变更事件，异步删除 Redis 缓存，实现业务代码零侵入
 */
@Slf4j
@Component
public class CanalCacheDeleteConsumer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 监听 Canal 发送的缓存删除消息
     */
    @RabbitListener(queues = CANAL_CACHE_DELETE_QUEUE)
    public void handleCacheDelete(CanalDataChangeEvent event, Channel channel, Message msg) {
        long deliveryTag = msg.getMessageProperties().getDeliveryTag();
        
        try {
            log.info("========== 收到 Canal 数据变更事件 ==========");
            log.info("数据库: {}", event.getDatabase());
            log.info("表名: {}", event.getTable());
            log.info("事件类型: {}", event.getType());
            log.info("主键ID: {}", event.getPkId());
            log.info("变更后数据: {}", JSONUtil.toJsonStr(event.getNewData()));
            
            // 根据表名和事件类型处理缓存删除
            processCacheDelete(event);
            
            // ACK 确认
            channel.basicAck(deliveryTag, false);
            log.info("缓存删除成功，已ACK确认");
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("========== 处理 Canal 数据变更事件失败 ==========");
            log.error("错误信息: {}", e.getMessage(), e);
            log.error("========================================");
            
            try {
                // 拒绝消息，不重新入队（避免无限重试）
                channel.basicNack(deliveryTag, false, false);
                log.warn("消息已拒绝，不再重试");
            } catch (IOException ioException) {
                log.error("消息拒绝失败", ioException);
            }
        }
    }

    /**
     * 根据表名处理缓存删除
     */
    private void processCacheDelete(CanalDataChangeEvent event) {
        String tableName = event.getTable();
        String pkId = event.getPkId();
        
        if (pkId == null || pkId.isEmpty()) {
            log.warn("主键ID为空，跳过缓存删除");
            return;
        }
        
        // 根据不同的表名执行对应的缓存删除逻辑
        switch (tableName) {
            case "tb_shop":
                deleteShopCache(pkId);
                break;
            case "tb_voucher":
                deleteVoucherCache(pkId);
                break;
            case "tb_seckill_voucher":
                deleteSeckillVoucherCache(pkId);
                break;
            case "tb_blog":
                deleteBlogCache(pkId);
                break;
            case "tb_user":
                deleteUserCache(pkId);
                break;
            default:
                log.info("未配置该表的缓存删除策略: {}", tableName);
                break;
        }
    }

    /**
     * 删除店铺缓存
     */
    private void deleteShopCache(String shopId) {
        String key = CACHE_SHOP_KEY + shopId;
        Boolean deleted = stringRedisTemplate.delete(key);
        log.info("删除店铺缓存: {}, 结果: {}", key, deleted);
        
        // 如果店铺被删除或更新，也需要从布隆过滤器中移除（可选，根据业务需求）
        // 注意：布隆过滤器不支持删除操作，这里仅删除缓存
    }

    /**
     * 删除优惠券缓存
     */
    private void deleteVoucherCache(String voucherId) {
        // 根据实际业务，删除相关的优惠券缓存
        String key = "cache:voucher:" + voucherId;
        Boolean deleted = stringRedisTemplate.delete(key);
        log.info("删除优惠券缓存: {}, 结果: {}", key, deleted);
    }

    /**
     * 删除秒杀优惠券缓存
     */
    private void deleteSeckillVoucherCache(String seckillVoucherId) {
        // 删除秒杀库存缓存
        String stockKey = SECKILL_STOCK_KEY + seckillVoucherId;
        Boolean deleted = stringRedisTemplate.delete(stockKey);
        log.info("删除秒杀库存缓存: {}, 结果: {}", stockKey, deleted);
        
        // 删除秒杀活动信息缓存
        String activityKey = SECKILL_ACTIVITY_KEY + seckillVoucherId;
        deleted = stringRedisTemplate.delete(activityKey);
        log.info("删除秒杀活动缓存: {}, 结果: {}", activityKey, deleted);
    }

    /**
     * 删除博客缓存
     */
    private void deleteBlogCache(String blogId) {
        String key = "cache:blog:" + blogId;
        Boolean deleted = stringRedisTemplate.delete(key);
        log.info("删除博客缓存: {}, 结果: {}", key, deleted);
    }

    /**
     * 删除用户缓存
     */
    private void deleteUserCache(String userId) {
        String key = LOGIN_USER_KEY + userId;
        Boolean deleted = stringRedisTemplate.delete(key);
        log.info("删除用户缓存: {}, 结果: {}", key, deleted);
    }
}
