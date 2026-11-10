# =============================================================================
#  p3_ttl_jitter_test.ps1 —— TTL 随机抖动（P3.3，防缓存雪崩）
#
#  验证四件事：
#   1) ★ 同一时刻创建的一批 key，TTL 各不相同（没抖动的话它们会一模一样）
#   2) 每个 TTL 都落在 [基础值, 基础值 + 抖动上限] 里（不是乱给的）
#   3) 抖动幅度是"真的分散"（最大最小之差是几百秒级，不是 1~2 秒的噪声）
#   4) 抖动只往上加，不会让 TTL 短于基础值
#
#  ★ 为什么"TTL 各不相同"才是关键断言
#    缓存是懒加载的：第一次被访问时才创建、同时设过期时间。
#    如果 TTL 是固定值，那么"在同一秒创建的一批 key"会【在同一秒一起过期】——
#    到点它们同时失效，请求同时穿透到 DB，这就是雪崩。
#    所以测试故意把一批 key 连续创建出来，然后看它们的 TTL 是不是被摊开了。
#    没有抖动时，这批 TTL 会完全相等（同一个 EXPIRE 秒数）。
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$RT_LIST  = @(2001, 2002, 2003, 2004, 2005, 2006)
$TTL_BASE   = 86400      # hotel.booking.inventory-cache-ttl-seconds
$TTL_JITTER = 3600       # hotel.booking.inventory-cache-ttl-jitter-seconds
$TTL_TOLER  = 15         # 读 TTL 时已经过了几秒，给点宽容

$from = D 5
$to   = D 45           # 40 天，通常跨两个月 -> 每个房型 2 个 bucket

function InvKeys {
    $r = RedisCmd @('KEYS', 'hotel:inv:*')
    return @(($r -split "`n") | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })
}

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
"  基础 TTL = $TTL_BASE 秒    抖动上限 = $TTL_JITTER 秒    区间 = $from ~ $to"

"`n=== 0. 重置（清掉所有库存缓存键）==="
ResetTestData | Out-Null
"  当前库存缓存键数 = $((InvKeys).Count)"

# ---------------------------------------------------------------------------
"`n=== 1. 连续创建一批 bucket，看 TTL 是否被摊开 ==="
foreach ($rt in $RT_LIST) {
    $r = Api POST "/booking/inv/rebuild?roomTypeId=$rt&from=$from&to=$to" ''
    if ($r.Http -ne 200) { "  !! 房型 $rt 重建失败 HTTP $($r.Http)"; }
}

$keys = InvKeys
$ttls = @()
foreach ($k in $keys) { $ttls += [int](RedisCmd @('TTL', $k)) }

"  一共 $($keys.Count) 个 bucket"
for ($i = 0; $i -lt $keys.Count; $i++) { "    {0,-40} TTL={1}" -f $keys[$i], $ttls[$i] }

$distinct = @($ttls | Sort-Object -Unique)
$minTtl = ($ttls | Measure-Object -Minimum).Minimum
$maxTtl = ($ttls | Measure-Object -Maximum).Maximum
$spread = $maxTtl - $minTtl
"  --"
"  TTL 取值个数 = $($distinct.Count)   最小 = $minTtl   最大 = $maxTtl   极差 = $spread"

Check 'created a batch of buckets' ($keys.Count -ge 6) "keys=$($keys.Count)"
Check 'TTL values are NOT all identical (jitter is working)' ($distinct.Count -ge 3) "distinct=$($distinct.Count) of $($keys.Count)"
Check 'the spread is hundreds of seconds, not noise' ($spread -ge 100) "spread=$spread"
Check 'every TTL is >= base (jitter only adds, never shortens)' ($minTtl -ge ($TTL_BASE - $TTL_TOLER)) "min=$minTtl base=$TTL_BASE"
Check 'every TTL is <= base + jitter' ($maxTtl -le ($TTL_BASE + $TTL_JITTER + $TTL_TOLER)) "max=$maxTtl limit=$($TTL_BASE+$TTL_JITTER)"

# ---------------------------------------------------------------------------
"`n=== 2. 同一个 bucket 连续重建两次，TTL 应该变（随机量每次都不同）==="
# 要探测"重建的那个房型"的桶，不能随便拿一个 —— 否则 TTL 只是自然衰减了几秒，
# 看起来就像"抖动没重新随机"（踩过：探测 2003 的桶却重建了 2001）
$probe = $keys | Where-Object { $_ -like "*{rt:$($RT_LIST[0])}*" } | Select-Object -First 1
"  探测键 = $probe"
$t1 = [int](RedisCmd @('TTL', $probe))
Api POST "/booking/inv/rebuild?roomTypeId=$($RT_LIST[0])&from=$from&to=$to" '' | Out-Null
$t2 = [int](RedisCmd @('TTL', $probe))
"  $probe : 重建前 TTL=$t1   重建后 TTL=$t2   差=$($t2-$t1)"
"  （差值为负且绝对值只有几秒 = 没抖动；差值几百秒 = 抖动生效）"
Check 'rebuilding re-randomises the TTL' ([Math]::Abs($t2 - $t1) -gt 30) "before=$t1 after=$t2"

# ---------------------------------------------------------------------------
"`n=== 3. 抖动不影响「缓存照常命中」==="
"  直接查一个刚被重建过、缓存里肯定有值的日期区间"
$ci = $from
$co = (Get-Date -Date ([datetime]::Parse($from)).AddDays(2)).ToString('yyyy-MM-dd')
$availBody = JsonBody @{ roomTypeId = 2001; checkIn = $ci; checkOut = $co; roomCount = 1 }
$avail = Api POST '/booking/availability' $availBody
"  POST /booking/availability -> HTTP $($avail.Http)  available=$($avail.Body.data.available)"
Check 'availability still works with jittered TTLs' ($avail.Http -eq 200) ''
Check 'the cache still answers correctly' ($avail.Body.data.available -eq $true) ''

# ---------------------------------------------------------------------------
"`n=== 4. 收尾：所有库存键都有 TTL，没有永久的 ==="
$noTtl = @($keys | Where-Object { [int](RedisCmd @('TTL', $_)) -le 0 }).Count
"  没有 TTL 的键 = $noTtl"
Check 'no inventory key is permanent' ($noTtl -eq 0) "noTtl=$noTtl"

Summarize
