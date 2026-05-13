--1.参数列表
--1.1优惠卷id
local voucherId = ARGV[1]
--1.2用户id
local userId = ARGV[2]
--1.3订单id
local orderId = ARGV[3]
--1.4重试次数（首次为0）
local retryCount = tonumber(ARGV[4]) or 0

--2.数据key
--2.1.库存key
local stockKey = "seckill:stock:" .. voucherId
--2.2.订单key（用户已购买Set）
local orderKey = "seckill:order:" .. voucherId
--2.3.活动信息key（Hash存储）
local activityKey = "seckill:activity:" .. voucherId
--2.4.待发队列key
local pendingQueueKey = "seckill:pending:queue"
--2.5.秒杀结果key
local resultKey = "seckill:result:" .. voucherId .. ":" .. userId

--3.脚本业务
--3.1.判断活动是否存在，获取活动时间
local activity = redis.call("hgetall", activityKey)
if (#activity == 0) then
    -- 活动不存在
    return 4
end

-- 解析活动时间
local beginTime = nil
local endTime = nil
for i = 1, #activity, 2 do
    if activity[i] == "beginTime" then
        beginTime = tonumber(activity[i + 1])
    elseif activity[i] == "endTime" then
        endTime = tonumber(activity[i + 1])
    end
end

--3.2.判断是否在活动时间内
local currentTime = tonumber(redis.call("time")[1]) -- 获取当前时间戳（秒），转换为数字
if (beginTime == nil or endTime == nil or currentTime < beginTime or currentTime > endTime) then
    -- 不在活动时间内
    return 5
end

--3.3.判断库存是否充足
local stock = tonumber(redis.call("get", stockKey))
if (stock == nil or stock <= 0) then
    -- 库存不足
    return 1
end

--3.4.判断用户是否重复抢购
if (redis.call("sismember", orderKey, userId) == 1) then
    -- 用户重复抢购
    return 2
end

--3.5.扣减库存
redis.call("decr", stockKey)

--3.6.记录用户抢购
redis.call("sadd", orderKey, userId)

--3.7.构建MQ消息
local message = string.format('{"userId":%s,"voucherId":%s,"id":%s,"retryCount":%d}', userId, voucherId, orderId, retryCount)

--3.8.将消息写入待发队列右端
redis.call("rpush", pendingQueueKey, message)

--3.9.设置初始结果为受理中（可选，也可以不设置，由消费者设置）
-- redis.call("setex", resultKey, 300, "PROCESSING")

return 0;
