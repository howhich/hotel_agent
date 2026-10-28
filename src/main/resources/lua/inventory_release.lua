-- =============================================================================
--  区间库存：释放单独一天
--
--  为什么按"天"释放而不是按区间：
--    取消/失效/改期都是按台账（booking_inventory_link）逐天还原的，
--    改期更是只释放差集里的那几天。所以脚本的粒度就是一天。
--
--  KEYS[1]   该天的月份 bucket hash
--  ARGV[1]   日期 yyyy-MM-dd
--  ARGV[2]   要释放的间数 n
--
--  返回：
--      {'OK',       date}              释放成功
--      {'NOTCACHED', date}             缓存里没这天 -> 什么都不做（DB 才是裁决者）
--      {'ANOMALY',  date, sold, n}      要释放的比已售还多 -> 归零并上报异常
--      {'BADREQ',   reason}            参数不合法
--
--  ★ 缓存里没有这天时【不是错误】：可能被 allkeys-lru 淘汰了，也可能这台
--    实例没预热。库存的事实来源是 DB 的 sold_rooms 和台账，缓存丢了不影响正确性。
-- =============================================================================

local d = ARGV[1]
local n = tonumber(ARGV[2])
if d == nil then
  return {'BADREQ', 'date'}
end
if n == nil or n <= 0 then
  return {'BADREQ', 'n'}
end

local sold = redis.call('HGET', KEYS[1], d)
if sold == false then
  return {'NOTCACHED', d}
end

local s = tonumber(sold)
if s < n then
  -- 不该发生：说明缓存与 DB 漂移了。归零并让调用方记告警，别把值减成负数。
  redis.call('HSET', KEYS[1], d, 0)
  return {'ANOMALY', d, tostring(s), ARGV[2]}
end

redis.call('HSET', KEYS[1], d, s - n)
return {'OK', d}
