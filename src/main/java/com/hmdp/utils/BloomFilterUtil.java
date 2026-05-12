package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * 布隆过滤器工具类 - 用于防止缓存穿透
 */
@Slf4j
@Component
public class BloomFilterUtil {

    @Resource
    private RedissonClient redissonClient;

    private RBloomFilter<Long> shopBloomFilter;
    
    // 布隆过滤器名称
    private static final String BLOOM_FILTER_SHOP_KEY = "bloom:filter:shop";
    
    // 预期元素数量
    private static final long EXPECTED_INSERTIONS = 10000L;
    
    // 期望的误判率 (0.01 = 1%)
    private static final double FALSE_POSITIVE_PROBABILITY = 0.01;

    /**
     * 应用启动时初始化布隆过滤器
     */
    @PostConstruct
    public void init() {
        try {
            // 创建或获取布隆过滤器
            shopBloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_SHOP_KEY);
            
            // 初始化布隆过滤器（如果不存在）
            if (!shopBloomFilter.isExists()) {
                shopBloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_POSITIVE_PROBABILITY);
                log.info("店铺布隆过滤器初始化成功，预期元素: {}, 误判率: {}", 
                    EXPECTED_INSERTIONS, FALSE_POSITIVE_PROBABILITY);
            } else {
                log.info("店铺布隆过滤器已存在");
            }
        } catch (Exception e) {
            log.error("布隆过滤器初始化失败", e);
        }
    }

    /**
     * 判断元素是否可能存在
     * @param id 元素ID
     * @return true-可能存在，false-一定不存在
     */
    public boolean mightContain(Long id) {
        if (id == null || shopBloomFilter == null) {
            return false;
        }
        return shopBloomFilter.contains(id);
    }

    /**
     * 添加元素到布隆过滤器
     * @param id 元素ID
     * @return 是否添加成功
     */
    public boolean add(Long id) {
        if (id == null || shopBloomFilter == null) {
            return false;
        }
        return shopBloomFilter.add(id);
    }

    /**
     * 批量添加元素到布隆过滤器
     * @param ids 元素ID列表
     */
    public void addAll(Iterable<Long> ids) {
        if (ids == null || shopBloomFilter == null) {
            return;
        }
        for (Long id : ids) {
            shopBloomFilter.add(id);
        }
        log.info("批量添加元素到布隆过滤器完成");
    }

    /**
     * 重置布隆过滤器
     */
    public void reset() {
        if (shopBloomFilter != null) {
            shopBloomFilter.delete();
            shopBloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_POSITIVE_PROBABILITY);
            log.info("布隆过滤器已重置");
        }
    }

    /**
     * 获取布隆过滤器中的元素数量（估算值）
     * @return 元素数量
     */
    public long getCount() {
        if (shopBloomFilter == null) {
            return 0;
        }
        return shopBloomFilter.count();
    }
}
