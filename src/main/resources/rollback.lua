-- 回滚Lua脚本 - 用于失败时回滚库存和用户ID
-- 参数列表
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 数据key
local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId

-- 回滚逻辑
-- 1. 恢复库存（加1）
redis.call("incr", stockKey)

-- 2. 从已购买Set中移除用户ID
redis.call("srem", orderKey, userId)

return 1
