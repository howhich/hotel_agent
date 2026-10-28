-- =============================================================================
--  订单超时延迟队列：原子认领到期任务
--
--  为什么必须用 Lua：
--    如果先 ZRANGEBYSCORE 取出来、再在外面逐个 ZREM，两个消费者会取到
--    同一批成员，然后各处理一遍。虽然 expireBooking() 有条件 UPDATE 兜着
--    （重复触发不会重复释放库存），但会白跑一遍、日志噪音，而且"谁负责"
--    这件事变得不清楚。
--
--    Redis 的 Lua 是原子执行的：ZRANGEBYSCORE 和紧随其后的 ZREM 之间
--    不会有别的命令插进来。所以「取 + 删」在一个脚本里 = 谁删掉谁处理。
--    这和台账释放的"一次性令牌"是同一个思路，只不过令牌换成了 ZREM 的返回值。
--
--  KEYS[1]  ZSet：score = 触发时刻的 epoch 毫秒，member = bookingId
--  ARGV[1]  当前时刻 epoch 毫秒
--  ARGV[2]  本次最多认领多少个
--
--  返回：被认领的 bookingId 列表（空表 = 当前没有到期任务）
-- =============================================================================

local key = KEYS[1]
local now = ARGV[1]
local limit = tonumber(ARGV[2])
if limit == nil or limit <= 0 then
    return {}
end

local members = redis.call('ZRANGEBYSCORE', key, '-inf', now, 'LIMIT', 0, limit)
if #members == 0 then
    return {}
end

local claimed = {}
for i = 1, #members do
    -- 脚本内原子，正常情况下必然是 1；防御性判断一下
    local removed = redis.call('ZREM', key, members[i])
    if removed == 1 then
        claimed[#claimed + 1] = members[i]
    end
end

return claimed
