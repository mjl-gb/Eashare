package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.ChaceClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private ChaceClient chaceClient;
    @Resource
    private RedisIdWorker redisIdWorker;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(500);
    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1);
        chaceClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1, shop, 1L, TimeUnit.SECONDS);
    }


    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
       Runnable task = () -> {
           for (int i = 0; i < 100; i++) {
               long id = redisIdWorker.nextId("order");
               System.out.println("id = " + id);
           }
           latch.countDown();
       };
       long start = System.currentTimeMillis();
       for(int i = 0; i < 300; i++){
           CACHE_REBUILD_EXECUTOR.submit(task);
       }
       latch.await();
       long end = System.currentTimeMillis();
       System.out.println("time = " + (end - start));
    }


}
