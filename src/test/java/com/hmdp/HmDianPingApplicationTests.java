package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.ChaceClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private ChaceClient chaceClient;
    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1);
        chaceClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1, shop, 1L, TimeUnit.SECONDS);
    }


}
