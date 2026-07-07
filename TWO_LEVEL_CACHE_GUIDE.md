# 二级缓存架构使用指南

## 📊 架构概述

本项目采用 **Caffeine + Redis** 的二级缓存架构，大幅提升热点数据访问性能。

### 缓存层级

```
┌─────────────────────────────────────────┐
│  L1: Caffeine 本地缓存 (微秒级)          │
│  - JVM 内存                              │
│  - 最大 200 条                           │
│  - 写入后 5 分钟过期                      │
│  - 访问后 2 分钟过期                      │
└─────────────────────────────────────────┘
                    ↓ 未命中
┌─────────────────────────────────────────┐
│  L2: Redis 分布式缓存 (毫秒级)            │
│  - 网络 + 内存操作                        │
│  - 可配置过期时间                          │
│  - 支持集群                               │
└─────────────────────────────────────────┘
                    ↓ 未命中
┌─────────────────────────────────────────┐
│  L3: MySQL 数据库 (毫秒-秒级)             │
│  - 持久化存储                             │
│  - 磁盘 I/O                              │
└─────────────────────────────────────────┘
```

---

## 🚀 快速开始

### 1. 在 Service 中使用二级缓存

#### 示例：查询店铺信息

```java
@Service
public class ShopServiceImpl implements IShopService {
    
    @Resource
    private TwoLevelCacheClient twoLevelCacheClient;
    
    @Override
    public Result queryById(Long id) {
        // 使用二级缓存查询
        Shop shop = twoLevelCacheClient.queryWithTwoLevelCache(
            "cache:shop:",      // 键前缀
            id,                  // 主键ID
            Shop.class,          // 返回类型
            30L,                 // Redis过期时间
            TimeUnit.MINUTES,    // 时间单位
            this::getById        // 数据库查询回调
        );
        
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }
}
```

#### 示例：查询博客信息

```java
@Service
public class BlogServiceImpl implements IBlogService {
    
    @Resource
    private TwoLevelCacheClient twoLevelCacheClient;
    
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = twoLevelCacheClient.queryWithTwoLevelCache(
            "cache:blog:",
            id,
            Blog.class,
            30L,
            TimeUnit.MINUTES,
            this::getById
        );
        
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        return Result.ok(blog);
    }
}
```

---

### 2. 更新数据时删除缓存

```java
@Transactional
@Override
public Result update(Shop shop) {
    Long id = shop.getId();
    if (id == null) {
        return Result.fail("店铺id不能为空");
    }
    
    String cacheKey = CACHE_SHOP_KEY + id;
    
    // 1. 更新数据库
    updateById(shop);
    
    // 2. 删除二级缓存（L1 + L2）
    twoLevelCacheClient.evict(cacheKey);
    
    return Result.ok();
}
```

---

## 📝 API 说明

### TwoLevelCacheClient 核心方法

#### 1. 查询二级缓存

```java
public <R, ID> R queryWithTwoLevelCache(
    String keyPrefix,           // 键前缀，如 "cache:shop:"
    ID id,                      // 主键ID
    Class<R> type,              // 返回类型
    Long time,                  // Redis过期时间
    TimeUnit unit,              // 时间单位
    Function<ID, R> dbFallback  // 数据库查询回调
)
```

**使用示例**：
```java
Shop shop = twoLevelCacheClient.queryWithTwoLevelCache(
    "cache:shop:",
    1L,
    Shop.class,
    30L,
    TimeUnit.MINUTES,
    this::getById
);
```

---

#### 2. 删除指定缓存

```java
public void evict(String key)
```

**使用示例**：
```java
twoLevelCacheClient.evict("cache:shop:1");
```

---

#### 3. 批量删除缓存（支持通配符）

```java
public void evictByPattern(String pattern)
```

**使用示例**：
```java
// 删除所有店铺缓存
twoLevelCacheClient.evictByPattern("cache:shop:*");
```

---

#### 4. 更新缓存

```java
public void update(String key, Object value, Long time, TimeUnit unit)
```

**使用示例**：
```java
twoLevelCacheClient.update("cache:shop:1", shop, 30L, TimeUnit.MINUTES);
```

---

#### 5. 获取缓存统计信息

```java
public String getCacheStats()
```

**使用示例**：
```java
String stats = twoLevelCacheClient.getCacheStats();
// 输出: "L1缓存统计 - 命中率: 85.23%, 总请求: 10000, 命中: 8523, 未命中: 1477, 当前大小: 156"
```

---

#### 6. 清空所有本地缓存

```java
public void clearLocalCache()
```

**使用示例**：
```java
twoLevelCacheClient.clearLocalCache();
```

---

## 🌐 REST API 接口

### 1. 获取缓存统计信息

```bash
GET /cache/stats
```

**响应示例**：
```json
{
  "code": 200,
  "data": "L1缓存统计 - 命中率: 85.23%, 总请求: 10000, 命中: 8523, 未命中: 1477, 当前大小: 156"
}
```

---

### 2. 清空本地缓存

```bash
POST /cache/clear/local
```

**响应示例**：
```json
{
  "code": 200,
  "data": "本地缓存已清空"
}
```

---

### 3. 删除指定缓存

```bash
DELETE /cache/cache:shop:1
```

**响应示例**：
```json
{
  "code": 200,
  "data": "缓存已删除: cache:shop:1"
}
```

---

### 4. 批量删除缓存

```bash
DELETE /cache/pattern/cache:shop:*
```

**响应示例**：
```json
{
  "code": 200,
  "data": "缓存已删除: cache:shop:*"
}
```

---

## ⚙️ 调优建议

### Caffeine 配置调整

根据实际业务场景调整缓存参数：

```java
private final Cache<String, Object> localCache = Caffeine.newBuilder()
    .maximumSize(500)  // 根据 JVM 内存调整（建议 100-1000）
    .expireAfterWrite(10, TimeUnit.MINUTES)  // 根据数据更新频率调整
    .expireAfterAccess(5, TimeUnit.MINUTES)  // 根据访问模式调整
    .recordStats()  // 开启统计
    .build();
```

**参数说明**：
- `maximumSize`: 最大缓存条目数，根据可用内存调整
- `expireAfterWrite`: 写入后过期时间，适合数据更新频繁的场景
- `expireAfterAccess`: 访问后过期时间，适合热点数据场景

---

## 📊 性能对比

| 场景 | 响应时间 | QPS | 说明 |
|------|---------|-----|------|
| **L1 命中** | 微秒级 (μs) | 百万级 | JVM 内存，无网络开销 |
| **L2 命中** | 毫秒级 (ms) | 万级 | 网络 + Redis 内存 |
| **L3 查询** | 毫秒-秒级 | 千级 | 数据库磁盘 I/O |

**典型效果**：
- 热点数据命中率：80%+
- 平均响应时间降低：60%+
- Redis 压力降低：70%+

---

## 🎯 适用场景

### ✅ 适合使用二级缓存

1. **读多写少**：店铺信息、商品详情、配置信息
2. **热点数据**：首页推荐、热门商品、排行榜
3. **高并发访问**：秒杀活动、限时抢购
4. **对响应时间敏感**：API 接口、移动端应用

### ❌ 不适合使用

1. **频繁更新**：实时库存、订单状态
2. **强一致性要求**：金融交易、账户余额
3. **内存敏感**：嵌入式设备、低配置服务器
4. **数据量大**：全量用户数据、历史订单

---

## 🔍 监控与运维

### 1. 查看缓存命中率

定期访问 `/cache/stats` 监控缓存效果：

```bash
curl http://localhost:8081/cache/stats
```

**理想指标**：
- L1 命中率：> 80%
- L2 命中率：> 90%
- 缓存大小：稳定在合理范围

---

### 2. 缓存预热

应用启动时预热热点数据：

```java
@PostConstruct
public void warmUpCache() {
    // 查询热门店铺
    List<Shop> hotShops = queryHotShops();
    
    // 写入二级缓存
    for (Shop shop : hotShops) {
        twoLevelCacheClient.update(
            "cache:shop:" + shop.getId(),
            shop,
            30L,
            TimeUnit.MINUTES
        );
    }
    
    log.info("缓存预热完成，共预热 {} 个店铺", hotShops.size());
}
```

---

### 3. 缓存清理策略

#### 定时清理
```java
@Scheduled(cron = "0 0 2 * * ?")  // 每天凌晨2点
public void cleanExpiredCache() {
    twoLevelCacheClient.clearLocalCache();
    log.info("定时清理本地缓存完成");
}
```

#### 手动清理
```bash
# 清空所有本地缓存
POST /cache/clear/local

# 删除特定缓存
DELETE /cache/cache:shop:1
```

---

## 🐛 常见问题

### Q1: 缓存不一致怎么办？

**A**: 采用 **Cache Aside Pattern**（旁路缓存模式）：
1. 更新数据库
2. 删除缓存（而不是更新缓存）
3. 下次查询时重新加载

优点：简单可靠，避免脏数据

---

### Q2: 缓存穿透怎么处理？

**A**: 二级缓存已内置空值缓存机制：
- 数据库查询结果为 null 时，缓存空值 `"NULL"`
- 设置较短的过期时间（2分钟）
- 配合布隆过滤器使用效果更佳

---

### Q3: 缓存雪崩如何预防？

**A**: 
1. 设置不同的过期时间（添加随机值）
2. 使用互斥锁防止同时重建缓存
3. 多级缓存架构本身就能缓解雪崩

---

### Q4: 如何评估缓存效果？

**A**: 通过 `/cache/stats` 接口监控：
- 命中率低于 60%：检查缓存策略
- 命中率高于 90%：考虑增大缓存容量
- 缓存大小持续增长：检查是否有内存泄漏

---

## 📚 最佳实践

1. **选择合适的缓存粒度**：按 ID 缓存，避免缓存大对象
2. **设置合理的过期时间**：热点数据短过期，冷数据长过期
3. **监控缓存命中率**：持续优化缓存策略
4. **定期清理无效缓存**：避免内存浪费
5. **配合 Canal 使用**：实现缓存自动失效

---

## 🎉 总结

二级缓存架构优势：
- ✅ **极速响应**：热点数据微秒级访问
- ✅ **降低压力**：减少 70%+ Redis 负载
- ✅ **高可用性**：Redis 故障时仍可提供部分服务
- ✅ **易于使用**：一行代码集成
- ✅ **完善监控**：实时统计缓存效果

立即开始使用，提升你的系统性能！🚀

