package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.ChaceClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static java.lang.Thread.sleep;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ChaceClient chaceClient;
    @Resource
    private com.hmdp.utils.BloomFilterUtil bloomFilterUtil;

    /**
     * 应用启动时预热布隆过滤器
     */
    @javax.annotation.PostConstruct
    public void initBloomFilter() {
        try {
            // 查询所有店铺ID
            List<Shop> shops = list();
            List<Long> shopIds = new ArrayList<>();
            for (Shop shop : shops) {
                shopIds.add(shop.getId());
            }
            
            // 批量添加到布隆过滤器
            bloomFilterUtil.addAll(shopIds);
            log.info("布隆过滤器预热完成，共添加 {} 个店铺ID", shopIds.size());
        } catch (Exception e) {
            log.error("布隆过滤器预热失败", e);
        }
    }

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //Shop shop = chaceClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, CACHE_SHOP_TTL, TimeUnit.MINUTES, this::getById);
        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        Shop shop = chaceClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, CACHE_SHOP_TTL, TimeUnit.MINUTES, this::getById);

        //逻辑过期解决缓存击穿
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /*public Shop queryWithPassThrough(Long id) {
        //1.从Redis中查询前端缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断缓存是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        //判断命中的是否是空值
        if (shopJson != null) {
            return null;
        }
        //3.不存在，根据id查询数据库，返回错误
        Shop shop = getById(id);
        if (shop == null) {
            //将空值缓存，返回错误
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //4.存在，写入Redis,返回
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }
    */
/*    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id) {

        String key = CACHE_SHOP_KEY + id;
        //1.从Redis中查询前端缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断缓存是否命中
        if (StrUtil.isBlank(shopJson)) {
            //3.缓存未命中，返回null
            return null;

        }
        //4.命中，将数据从json反序列化成java对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = BeanUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1.未过期，直接返回
            return shop;
        }
        //5.2.过期，缓存重建
        //6.缓存重建
        //6.1尝试获取互斥锁
        String lock = LOCK_SHOP_KEY + id;
            if (tryLock(lock)) {
                //6.2获取锁成功，开启独立线程，进行重建
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        this.saveShop2Redis(id, 30L);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        unLock(lock);
                    }
                });
            }
        //6.3获取锁失败，返回过期商铺信息
        return shop;
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(getById(id));
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);

    }
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }*/

    /*public Shop queryWithMutex(Long id) {
        //1.从Redis中查询前端缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断缓存是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        //3.判断命中的是否是空值
        if (shopJson != null) {
            return null;
        }

        //4.实现缓存重建
        //4.1 获取互斥锁
        //4.2 判断是否获取锁成功
        //4.3 成功，获取锁，进行业务逻辑处理
        //4.3.失败，休眠并重试
        String lock = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lock);
            if (!isLock) {
                sleep(50);
                return queryWithMutex(id);
            }
            //5.不存在，根据id查询数据库，返回错误
            shop = getById(id);
            Thread.sleep(200);
            if (shop == null) {
                //将空值缓存，返回错误
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入Redis,返回
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放锁
            unLock(lock);
        }

        return shop;
    }
*/



    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        
        //3.如果店铺存在，确保布隆过滤器中有该ID
        if (getById(id) != null) {
            bloomFilterUtil.add(id);
        }
        
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //查询redis,按照距离排序，分页
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(key,
                        new Circle(new Point(x, y), new Distance(5000, Metrics.NEUTRAL)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(end)
                );
        if (results == null) {
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            //没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        //截取from-end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());

        list.stream().skip(from).forEach(
                geoResult -> {
                    //获取店铺id
                    String shopIdStr = geoResult.getContent().getName();
                    ids.add(Long.valueOf(shopIdStr));
                    //获取距离
                    distanceMap.put(shopIdStr, geoResult.getDistance());
                }
        );

        //根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        // 返回数据
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
