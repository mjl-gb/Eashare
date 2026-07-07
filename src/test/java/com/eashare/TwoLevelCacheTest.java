package com.eashare;

import com.eashare.service.IShopService;
import com.eashare.utils.TwoLevelCacheClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

/**
 * 二级缓存测试
 */
@Slf4j
@SpringBootTest
public class TwoLevelCacheTest {

    @Resource
    private TwoLevelCacheClient twoLevelCacheClient;
    
    @Resource
    private IShopService shopService;

    /**
     * 测试二级缓存查询
     */
    @Test
    public void testTwoLevelCache() {
        log.info("========== 开始测试二级缓存 ==========");
        
        // 第一次查询（应该从数据库加载）
        log.info("第1次查询：");
        long start1 = System.currentTimeMillis();
        shopService.queryById(1L);
        long time1 = System.currentTimeMillis() - start1;
        log.info("耗时: {}ms", time1);
        
        // 第二次查询（应该从 L1 缓存命中）
        log.info("\n第2次查询：");
        long start2 = System.currentTimeMillis();
        shopService.queryById(1L);
        long time2 = System.currentTimeMillis() - start2;
        log.info("耗时: {}ms", time2);
        
        // 第三次查询（应该从 L1 缓存命中）
        log.info("\n第3次查询：");
        long start3 = System.currentTimeMillis();
        shopService.queryById(1L);
        long time3 = System.currentTimeMillis() - start3;
        log.info("耗时: {}ms", time3);
        
        // 查看缓存统计
        String stats = twoLevelCacheClient.getCacheStats();
        log.info("\n{}", stats);
        
        log.info("\n========== 测试完成 ==========");
        log.info("预期结果：");
        log.info("- 第1次查询：从数据库加载，耗时较长");
        log.info("- 第2、3次查询：从 L1 缓存命中，耗时极短（微秒级）");
        log.info("- 命中率应该接近 66.67%（2次命中 / 3次请求）");
    }

    /**
     * 测试缓存删除
     */
    @Test
    public void testCacheEvict() {
        log.info("========== 测试缓存删除 ==========");
        
        // 先查询，建立缓存
        shopService.queryById(1L);
        log.info("已建立缓存");
        
        // 查看统计
        log.info("删除前: {}", twoLevelCacheClient.getCacheStats());
        
        // 删除缓存
        twoLevelCacheClient.evict("cache:shop:1");
        log.info("已删除缓存");
        
        // 再次查询（应该重新从数据库加载）
        shopService.queryById(1L);
        log.info("再次查询完成");
        
        // 查看统计
        log.info("删除后: {}", twoLevelCacheClient.getCacheStats());
        
        log.info("========== 测试完成 ==========");
    }

    /**
     * 测试批量查询性能
     */
    @Test
    public void testBatchQuery() {
        log.info("========== 测试批量查询性能 ==========");
        
        int count = 100;
        
        // 预热缓存
        log.info("预热缓存...");
        for (long i = 1; i <= 10; i++) {
            shopService.queryById(i);
        }
        
        // 批量查询
        log.info("开始批量查询 {} 次...", count);
        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            long id = (i % 10) + 1;  // 循环查询 1-10 号店铺
            shopService.queryById(id);
        }
        long elapsed = System.currentTimeMillis() - start;
        
        log.info("总耗时: {}ms", elapsed);
        log.info("平均耗时: {}ms/次", elapsed / count);
        log.info("QPS: {}", count * 1000 / elapsed);
        
        // 查看缓存统计
        log.info("\n{}", twoLevelCacheClient.getCacheStats());
        
        log.info("========== 测试完成 ==========");
    }
}
