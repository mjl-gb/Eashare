package com.eashare.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.eashare.utils.RedisConstants.*;

@Component
@Slf4j
public class ChaceClient {
    private StringRedisTemplate stringRedisTemplate;
    private BloomFilterUtil bloomFilterUtil;

    public ChaceClient(StringRedisTemplate stringRedisTemplate, BloomFilterUtil bloomFilterUtil) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.bloomFilterUtil = bloomFilterUtil;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,  Long time, TimeUnit unit,Function<ID, R> dbFallback) {
        String key = keyPrefix + id;
            
        // 【布隆过滤器】先判断id是否可能存在
        if (id instanceof Long && !bloomFilterUtil.mightContain((Long) id)) {
            log.debug("布隆过滤器拦截：id={} 一定不存在", id);
            return null;
        }
            
        //1.从 Redis中查询前端缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否存在
        if (StrUtil.isNotBlank(Json)) {
            return JSONUtil.toBean(Json, type);
    
        }
        //判断命中的是否是空值
        if (Json != null) {
            return null;
        }
        //3.不存在，根据id查询数据库，返回错误
        R r = dbFallback.apply(id);
        if (r == null) {
            //将空值缓存，返回错误
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //4.存在，写入Redis,返回
        this.set(key, r, time, unit);
            
        // 【布隆过滤器】将存在的id添加到布隆过滤器
        if (id instanceof Long) {
            bloomFilterUtil.add((Long) id);
        }
    
        return r;
    }

    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Long time, TimeUnit unit, Function<ID, R> dbFallback) {

        String key = keyPrefix + id;
        //1.从Redis中查询前端缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否命中
        if (StrUtil.isBlank(Json)) {
            //3.缓存未命中，返回null
            return null;

        }
        //4.命中，将数据从json反序列化成java对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = BeanUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1.未过期，直接返回
            return r;
        }
        //5.2.过期，缓存重建
        //6.缓存重建
        //6.1尝试获取互斥锁
        String lock = LOCK_SHOP_KEY + id;
        if (tryLock(lock)) {
            //6.2获取锁成功，开启独立线程，进行重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入Redis
                    this.setWithLogicalExpire(key, r1, time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lock);
                }
            });
        }
        //6.3获取锁失败，返回过期商铺信息
        return r;
    }
    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);

    }
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }


}