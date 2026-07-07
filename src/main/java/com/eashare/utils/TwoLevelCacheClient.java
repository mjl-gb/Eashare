package com.eashare.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 二级缓存客户端（Caffeine + Redis）
 * 
 * 架构说明：
 * L1: Caffeine 本地缓存 - 微秒级响应，存储热点数据
 * L2: Redis 分布式缓存 - 毫秒级响应，存储全量数据
 * L3: MySQL 数据库 - 持久化存储
 * 
 * 查询策略：L1 → L2 → L3
 * 更新策略：先更新数据库，再删除 L1 和 L2
 */
@Slf4j
@Component
public class TwoLevelCacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * Caffeine 本地缓存实例
     * 配置说明：
     * - 最大容量：200个条目
     * - 写入后5分钟过期
     * - 访问后2分钟过期
     */
    private final Cache<String, Object> localCache = Caffeine.newBuilder()
            .maximumSize(200)  // 最大缓存200个条目
            .expireAfterWrite(5, TimeUnit.MINUTES)  // 写入后5分钟过期
            .expireAfterAccess(2, TimeUnit.MINUTES)  // 访问后2分钟过期
            .recordStats()  // 开启统计功能
            .build();

    /**
     * 查询二级缓存
     * 
     * @param keyPrefix 键前缀
     * @param id 主键ID
     * @param type 返回类型
     * @param time Redis过期时间
     * @param unit 时间单位
     * @param dbFallback 数据库查询回调
     * @return 查询结果
     */
    public <R, ID> R queryWithTwoLevelCache(
            String keyPrefix, 
            ID id, 
            Class<R> type, 
            Long time, 
            TimeUnit unit,
            java.util.function.Function<ID, R> dbFallback) {
        
        String key = keyPrefix + id;
        
        // ========== L1: 查询 Caffeine 本地缓存 ==========
        Object localResult = localCache.getIfPresent(key);
        if (localResult != null) {
            log.debug("L1缓存命中: {}", key);
            
            // 处理空值缓存
            if ("NULL".equals(localResult)) {
                return null;
            }
            
            return JSONUtil.toBean((String) localResult, type);
        }
        
        log.debug("L1缓存未命中，查询L2: {}", key);
        
        // ========== L2: 查询 Redis 分布式缓存 ==========
        String redisJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(redisJson)) {
            log.debug("L2缓存命中: {}", key);
            
            R result = JSONUtil.toBean(redisJson, type);
            
            // 回填 L1 缓存
            localCache.put(key, redisJson);
            
            return result;
        }
        
        // 处理空值缓存
        if (redisJson != null) {
            log.debug("L2缓存命中空值: {}", key);
            return null;
        }
        
        log.debug("L2缓存未命中，查询数据库: {}", key);
        
        // ========== L3: 查询数据库 ==========
        R dbResult = dbFallback.apply(id);
        
        if (dbResult == null) {
            // 缓存空值，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
            localCache.put(key, "NULL");
            log.debug("数据库未命中，缓存空值: {}", key);
            return null;
        }
        
        // ========== 写入二级缓存 ==========
        String jsonStr = JSONUtil.toJsonStr(dbResult);
        
        // 写入 L2 Redis（设置过期时间）
        stringRedisTemplate.opsForValue().set(key, jsonStr, time, unit);
        
        // 写入 L1 Caffeine（自动过期）
        localCache.put(key, jsonStr);
        
        log.debug("数据写入二级缓存: {}", key);
        
        return dbResult;
    }

    /**
     * 删除二级缓存
     * 
     * @param key 缓存键
     */
    public void evict(String key) {
        // 删除 L1 缓存
        localCache.invalidate(key);
        
        // 删除 L2 缓存
        stringRedisTemplate.delete(key);
        
        log.debug("删除二级缓存: {}", key);
    }

    /**
     * 批量删除二级缓存（支持通配符）
     * 
     * @param pattern 匹配模式，如 "cache:shop:*"
     */
    public void evictByPattern(String pattern) {
        // 删除 L2 Redis 中的匹配键
        java.util.Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            log.debug("批量删除L2缓存: {} 个键", keys.size());
        }
        
        // L1 缓存会自然过期，无需手动清理
        log.debug("批量删除缓存: {}", pattern);
    }

    /**
     * 更新二级缓存
     * 
     * @param key 缓存键
     * @param value 新值
     * @param time Redis过期时间
     * @param unit 时间单位
     */
    public void update(String key, Object value, Long time, TimeUnit unit) {
        String jsonStr = JSONUtil.toJsonStr(value);
        
        // 更新 L2 Redis
        stringRedisTemplate.opsForValue().set(key, jsonStr, time, unit);
        
        // 更新 L1 Caffeine
        localCache.put(key, jsonStr);
        
        log.debug("更新二级缓存: {}", key);
    }

    /**
     * 获取缓存统计信息
     * 
     * @return 统计信息字符串
     */
    public String getCacheStats() {
        return String.format(
            "L1缓存统计 - 命中率: %.2f%%, 总请求: %d, 命中: %d, 未命中: %d, 当前大小: %d",
            localCache.stats().hitRate() * 100,
            localCache.stats().requestCount(),
            localCache.stats().hitCount(),
            localCache.stats().missCount(),
            localCache.estimatedSize()
        );
    }

    /**
     * 清空所有本地缓存
     */
    public void clearLocalCache() {
        localCache.invalidateAll();
        log.info("清空L1本地缓存");
    }
}
