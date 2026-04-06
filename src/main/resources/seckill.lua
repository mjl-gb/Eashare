--1.参数列表
--1.1优惠卷id
local voucherId = ARGV[1]
--1.2用户id
local userId = ARGV[2]
--1.3订单id
local orderId = ARGV[3]


--2.数据key
--2.1.库存key
local stockKey = "seckill:stock:" .. voucherId
--2.2.订单key
local orderKey = "seckill:order:" .. userId
--3.脚本业务
--3.1.判断库存是否充足
if (tonumber(redis.call("get", stockKey)) <= 0) then
    --3.1.库存不足
    return 1
end
--3.2.判断用户是否重复抢购
if (redis.call("sismember", orderKey, userId) == 1) then
    --3.3.用户重复抢购
    return 2
end
--3.4.扣减库存
redis.call("incrby", stockKey, -1)
--3.5.记录用户抢购
redis.call("sadd", orderKey, userId)
--3.6发送消息到消息队列中
local channel = "stream.orders"
redis.call("xadd", channel, "*", "userId", userId, "voucherId", voucherId, "id", orderId)

return 0;