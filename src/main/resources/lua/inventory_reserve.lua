-- =============================================================================
--  区间库存：预检 + 预占（一次 Lua 内完成整个日期区间）
--
--  ★ 必须两趟：先【全查】，全部通过再【全扣】。
--    不能边查边扣 —— 因为第 3 天不够时前 2 天已经扣掉了，
--    在 Lua 里做补偿既别扭又容易漏。
--
--  KEYS[1..M]  各月份 bucket 的 hash key（同一房型，hash tag 保证落同一 slot）
--  ARGV[1]     要占的间数 n
--  ARGV[2]     该房型总房量 total
--  ARGV[3..]   交替的 (keyIndex, date) 对；keyIndex 是 1-based 的 KEYS 下标
--
--  返回（全部是字符串，方便 Java 侧统一用 String 解析）：
--      {'OK'}                   全部成功
--      {'MISS',   date}         该天不在缓存里 -> 调用方回源 DB 后重试
--      {'REJECT', date}         该天剩余不足
--      {'BADREQ', reason}       参数不合法
-- =============================================================================

local n = tonumber(ARGV[1])
local total = tonumber(ARGV[2])
if n == nil or n <= 0 then
  return {'BADREQ', 'n'}
end
if total == nil or total <= 0 then
  return {'BADREQ', 'total'}
end

-- ---------- 第一趟：全查 ----------
local i = 3
while i <= #ARGV do
  local ki = tonumber(ARGV[i])
  local d = ARGV[i + 1]
  if ki == nil or d == nil then
    return {'BADREQ', 'pairs'}
  end
  local k = KEYS[ki]
  if k == nil then
    return {'BADREQ', 'key'}
  end

  local sold = redis.call('HGET', k, d)
  if sold == false then
    -- 这天没进缓存 —— 交给调用方回源，本脚本不做任何修改
    return {'MISS', d}
  end
  if tonumber(sold) + n > total then
    return {'REJECT', d}
  end
  i = i + 2
end

-- ---------- 第二趟：全扣 ----------
-- 能走到这里说明区间内每一天都够
i = 3
while i <= #ARGV do
  local ki = tonumber(ARGV[i])
  local d = ARGV[i + 1]
  redis.call('HINCRBY', KEYS[ki], d, n)
  i = i + 2
end

return {'OK'}
