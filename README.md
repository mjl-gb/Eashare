# Eashare - 电商秒杀系统

基于 Spring Boot 的高并发电商秒杀系统，实现了完整的秒杀流程、二级缓存、分布式锁、消息队列等核心功能。

## 技术栈

| 分类 | 技术 | 版本 |
| :--- | :--- | :--- |
| 语言 | Java | 1.8 |
| 框架 | Spring Boot | 2.3.12.RELEASE |
| ORM | MyBatis Plus | 3.4.3 |
| 数据库 | MySQL | 5.1.47 |
| 缓存 | Redis | - |
| 本地缓存 | Caffeine | 2.9.3 |
| 分布式锁 | Redisson | 3.13.6 |
| 消息队列 | RabbitMQ | - |
| Binlog监听 | Canal | 1.1.5 |
| 工具库 | Hutool | 5.7.17 |

## 项目结构

```
Eashare/
├── src/main/java/com/eashare/
│   ├── config/          # 配置类
│   │   ├── CanalConfig.java      # Canal监听配置
│   │   ├── MvcConfig.java        # MVC配置（拦截器）
│   │   ├── RabbitMQConfig.java   # RabbitMQ队列配置
│   │   ├── RedissonConfig.java   # Redisson配置
│   │   └── WebExceptionAdvice.java # 全局异常处理
│   ├── controller/      # 控制器
│   ├── dto/             # 数据传输对象
│   ├── entity/          # 实体类
│   ├── mapper/          # MyBatis Plus Mapper
│   ├── mq/              # 消息队列消费者
│   │   ├── SeckillOrderConsumer.java   # 秒杀订单消费者
│   │   ├── SeckillDeadLetterConsumer.java # 死信队列消费者
│   │   └── CanalCacheDeleteConsumer.java  # Canal缓存删除消费者
│   ├── service/         # 业务逻辑层
│   │   └── ReliableDeliveryService.java # MQ可靠投递服务
│   ├── utils/           # 工具类
│   │   ├── TwoLevelCacheClient.java # 二级缓存客户端
│   │   ├── SimpleRedisLock.java     # Redis分布式锁
│   │   ├── RedisIdWorker.java       # ID生成器
│   │   └── UserHolder.java          # 用户上下文
│   └── Eashare.java     # 启动类
├── src/main/resources/
│   ├── db/              # 数据库脚本
│   ├── mapper/          # MyBatis XML映射
│   ├── application-template.yaml # 应用配置模板
│   ├── seckill.lua      # 秒杀Lua脚本
│   ├── unlock.lua       # 解锁Lua脚本
│   └── rollback.lua     # 回滚Lua脚本
└── pom.xml              # Maven依赖
```

## 核心功能

### 1. 秒杀系统

**流程设计**：
- **资格校验（同步）**：通过 Lua 脚本原子性校验活动状态、时间、库存、重复购买
- **订单创建（异步）**：通过 RabbitMQ 异步处理订单创建，实现流量削峰

**关键特性**：
- Redis 库存扣减，支持高并发
- 一人一单限制
- 活动时间校验
- 死信队列处理失败订单
- 消息重试机制（最大3次）

### 2. 二级缓存

**架构设计**：
- **L1**：Caffeine 本地缓存（微秒级响应）
- **L2**：Redis 分布式缓存（毫秒级响应）
- **L3**：MySQL 数据库（持久化存储）

**更新策略**：先更新数据库 → 删除缓存 → 下次读取自动回填

**缓存一致性**：通过 Canal 监听 MySQL binlog，自动删除过期缓存

**详细指南**：参考 [TWO_LEVEL_CACHE_GUIDE.md](TWO_LEVEL_CACHE_GUIDE.md) 获取二级缓存的完整设计文档

### 3. 分布式锁

**实现方式**：
- **SimpleRedisLock**：基于 Redis SETNX，非重入锁
- **Redisson RLock**：基于 Redlock 算法，支持锁续期、重入

**使用场景**：秒杀下单、缓存更新等需要分布式互斥的场景

### 4. 用户系统

- 手机验证码登录
- Token 有效期刷新
- ThreadLocal 用户上下文管理

### 5. 商户服务

- 商户信息 CRUD
- 商户类型管理
- 缓存预热

### 6. 社交功能

- 博客发布与点赞
- 用户关注
- 消息推送

### 7. 消息队列可靠投递

**核心组件**：`ReliableDeliveryService` 负责消息的可靠投递

**设计要点**：
- Redis 待发队列作为消息缓冲区
- 后台线程定时扫描待发队列，投递到 RabbitMQ
- 消息确认机制，失败自动重试
- 消息历史记录，便于问题排查

## 快速开始

### 环境要求

- JDK 1.8+
- MySQL 5.7+
- Redis 6.0+
- RabbitMQ 3.8+
- Canal Server 1.1.5

### 数据库初始化

```sql
-- 创建数据库
CREATE DATABASE IF NOT EXISTS heimadianping DEFAULT CHARACTER SET utf8mb4;

-- 导入数据
source src/main/resources/db/hmdp.sql;
```

### 配置文件

复制模板配置文件并修改：

```bash
cp src/main/resources/application-template.yaml src/main/resources/application.yaml
```

配置文件支持环境变量：

| 环境变量 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `DB_USERNAME` | root | 数据库用户名 |
| `DB_PASSWORD` | 空 | 数据库密码 |
| `REDIS_HOST` | localhost | Redis 主机 |
| `REDIS_PORT` | 6379 | Redis 端口 |
| `REDIS_PASSWORD` | 空 | Redis 密码 |
| `RABBITMQ_HOST` | localhost | RabbitMQ 主机 |
| `RABBITMQ_PORT` | 5672 | RabbitMQ 端口 |
| `RABBITMQ_USERNAME` | guest | RabbitMQ 用户名 |
| `RABBITMQ_PASSWORD` | guest | RabbitMQ 密码 |
| `CANAL_HOST` | localhost | Canal Server 主机 |
| `CANAL_PORT` | 11111 | Canal Server 端口 |

### 启动服务

```bash
cd Eashare
mvn spring-boot:run
```

## API 接口

### 秒杀接口

| 接口 | 方法 | 描述 |
| :--- | :--- | :--- |
| `/voucher-order/seckill/{id}` | POST | 秒杀下单 |

### 优惠券接口

| 接口 | 方法 | 描述 |
| :--- | :--- | :--- |
| `/voucher` | POST | 新增普通券 |
| `/voucher/seckill` | POST | 新增秒杀券 |
| `/voucher/list/{shopId}` | GET | 查询店铺优惠券列表 |
| `/voucher/warmup` | POST | 预热秒杀库存到Redis |
| `/voucher/result/{voucherId}` | GET | 查询秒杀结果 |

### 商户接口

| 接口 | 方法 | 描述 |
| :--- | :--- | :--- |
| `/shop/{id}` | GET | 查询商户详情 |
| `/shop` | POST | 新增商户 |
| `/shop` | PUT | 更新商户 |
| `/shop/of/type` | GET | 根据类型分页查询商户 |
| `/shop/of/name` | GET | 根据名称分页查询商户 |

### 商户类型接口

| 接口 | 方法 | 描述 |
| :--- | :--- | :--- |
| `/shop-type/list` | GET | 获取商户类型列表 |

### 用户接口

| 接口 | 方法 | 描述 |
| :--- | :--- | :--- |
| `/user/code` | POST | 发送手机验证码 |
| `/user/login` | POST | 用户登录 |
| `/user/logout` | POST | 用户登出 |
| `/user/me` | GET | 获取当前用户 |
| `/user/info/{id}` | GET | 获取用户详情 |
| `/user/{id}` | GET | 根据ID查询用户 |
| `/user/sign` | POST | 用户签到 |
| `/user/sign/count` | GET | 查询签到天数 |

### 博客接口

| 接口 | 方法 | 描述 |
| :--- | :--- | :--- |
| `/blog` | POST | 发布博客 |
| `/blog/like/{id}` | PUT | 点赞博客 |
| `/blog/of/me` | GET | 查询我的博客 |
| `/blog/hot` | GET | 查询热门博客 |
| `/blog/{id}` | GET | 查询博客详情 |
| `/blog/likes/{id}` | GET | 查询博客点赞列表 |
| `/blog/of/user` | GET | 查询用户博客 |
| `/blog/of/follow` | GET | 查询关注的博客 |

### 缓存管理接口

| 接口 | 方法 | 描述 |
| :--- | :--- | :--- |
| `/cache/stats` | GET | 获取二级缓存统计信息 |
| `/cache/clear/local` | POST | 清空本地缓存 |
| `/cache/{key}` | DELETE | 删除指定缓存 |
| `/cache/pattern/{pattern}` | DELETE | 批量删除缓存（支持通配符） |

### 关注接口

| 接口 | 方法 | 描述 |
| :--- | :--- | :--- |
| `/follow/{id}/{isFollow}` | PUT | 关注/取消关注用户 |
| `/follow/or/not/{id}` | GET | 判断是否关注某用户 |
| `/follow/common/{id}` | GET | 查询共同关注 |

### 文件上传接口

| 接口 | 方法 | 描述 |
| :--- | :--- | :--- |
| `/upload/blog` | POST | 上传博客图片 |
| `/upload/blog/delete` | GET | 删除博客图片 |

### 消息队列历史接口

| 接口 | 方法 | 描述 |
| :--- | :--- | :--- |
| `/mq-history/success/list` | GET | 查询成功记录列表 |
| `/mq-history/failure/list` | GET | 查询失败记录列表 |
| `/mq-history/order/{orderId}` | GET | 根据订单ID查询详细历史 |
| `/mq-history/keys` | GET | 查询所有历史记录key |
| `/mq-history/stats` | GET | 获取统计信息 |
| `/mq-history/clear` | DELETE | 清空历史记录 |

### 布隆过滤器接口

| 接口 | 方法 | 描述 |
| :--- | :--- | :--- |
| `/bloom/stats` | GET | 获取布隆过滤器统计信息 |
| `/bloom/contains/{id}` | GET | 判断ID是否可能存在 |
| `/bloom/add/{id}` | POST | 手动添加ID到布隆过滤器 |
| `/bloom/reset` | POST | 重置布隆过滤器 |

## 核心流程图

### 秒杀流程

```
用户请求 → VoucherOrderController → VoucherOrderServiceImpl
                                       ↓
                                  Lua脚本校验(Redis)
                                       ↓
                          校验通过 → 写入待发队列
                          校验失败 → 返回错误信息
                                       ↓
                           SeckillOrderConsumer
                                       ↓
                              获取分布式锁
                                       ↓
                          幂等性检查 → 扣减库存 → 创建订单
                                       ↓
                              设置秒杀结果 → ACK消息
```

### 二级缓存查询流程

```
查询请求 → TwoLevelCacheClient
              ↓
         L1: Caffeine 本地缓存
              ↓ 未命中
         L2: Redis 分布式缓存
              ↓ 未命中
         L3: MySQL 数据库
              ↓
         写入 L1 + L2 缓存
```

### Canal 缓存一致性流程

```
MySQL 数据变更 → binlog → Canal Server
                              ↓
                         CanalClient (Eashare)
                              ↓
                         RabbitMQ 消息队列
                              ↓
                   CanalCacheDeleteConsumer
                              ↓
                         删除 L1 + L2 缓存
```

## 关键技术实现

### 1. Lua 脚本秒杀校验

```lua
-- seckill.lua
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

-- 校验活动时间、库存、重复购买
-- 扣减库存、记录用户、写入队列
```

### 2. 分布式锁实现

**SimpleRedisLock**：

```java
public boolean tryLock(long timeoutSec) {
    String threadId = ID_PREFIX + Thread.currentThread().getId();
    return stringRedisTemplate.opsForValue()
        .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
}
```

**Redisson RLock**：

```java
RLock lock = redissonClient.getLock("lock:order:" + userId);
boolean isLocked = lock.tryLock();
```

### 3. 二级缓存查询

```java
public <R, ID> R queryWithTwoLevelCache(String keyPrefix, ID id, Class<R> type, 
                                        Long time, TimeUnit unit, 
                                        Function<ID, R> dbFallback) {
    // L1: Caffeine
    Object localResult = localCache.getIfPresent(key);
    if (localResult != null) { return JSONUtil.toBean((String) localResult, type); }
    
    // L2: Redis
    String redisJson = stringRedisTemplate.opsForValue().get(key);
    if (StrUtil.isNotBlank(redisJson)) {
        localCache.put(key, redisJson); // 回填 L1
        return JSONUtil.toBean(redisJson, type);
    }
    
    // L3: MySQL
    R dbResult = dbFallback.apply(id);
    // 写入缓存
    return dbResult;
}
```

## 部署建议

### 生产环境配置

1. **Redis 集群**：使用 Redis Cluster 或 Sentinel 保证高可用
2. **RabbitMQ 集群**：配置镜像队列保证消息不丢失
3. **MySQL 主从**：读写分离，提升读性能
4. **Canal 高可用**：配置 Canal Server 集群
5. **服务集群**：多实例部署，负载均衡

### 性能优化

- **热点数据预热**：秒杀开始前将活动信息加载到缓存
- **限流降级**：使用 Sentinel 或 Hystrix 进行限流
- **CDN 加速**：静态资源使用 CDN 分发
- **连接池优化**：合理配置数据库、Redis 连接池大小

## 监控指标

建议监控以下指标：

| 指标 | 说明 |
| :--- | :--- |
| Redis 命中率 | 缓存命中率 |
| Caffeine 命中率 | 本地缓存命中率 |
| RabbitMQ 消息堆积 | 队列消息数量 |
| 秒杀成功率 | 下单成功/失败比例 |
| 接口响应时间 | 各接口平均响应时间 |

## License

MIT License