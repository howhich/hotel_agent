# =============================================================================
#  p3_price_cache_test.ps1 —— 价格缓存 + 改价失效（P3.4）
#
#  验证六件事：
#   1) 价格走缓存：第二次报价不再查 room_price
#   2) ★ 改价之后，报价【立刻】变成新价（说明缓存被失效了）
#   3) 只失效那一天，同房型别的日期不受影响
#   4) 改价真的落到了数据库（不只是缓存的事）
#   5) 没有价格日历的日期 -> 409，而且缓存里记了占位符，重复请求不再查库（防穿透）
#   6) 缓存键的结构与 TTL 符合预期
#
#  ★ 计数依然用 general_log 数 "FROM room_price"，
#    不用 Com_select 差值 —— 那里面混着 JDBC 的
#    SELECT @@session.transaction_read_only，看绝对值得不出结论（P3.2 踩过）。
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$RT = 2001
$ci  = D 100          # 第一个住宿夜（在 180 天价格日历之内）
$co  = D 101          # 1 晚
$d2  = D 101          # 第二个住宿夜
$co2 = D 102          # 2 晚（$ci ~ $co2）
$far = D 200          # 超出价格日历（没有价格）

function PriceSelectsDuring([scriptblock]$action) {
    Sql "SET GLOBAL log_output='TABLE'" | Out-Null
    Sql "SET GLOBAL general_log='ON'" | Out-Null
    Sql "TRUNCATE TABLE mysql.general_log" | Out-Null
    & $action
    Sql "SET GLOBAL general_log='OFF'" | Out-Null
    $n = Sql "SELECT COUNT(*) FROM mysql.general_log WHERE command_type IN ('Query','Execute') AND argument LIKE '%FROM room_price%'"
    Sql "SET GLOBAL log_output='FILE'" | Out-Null
    return [int]$n
}

function Quote([string]$a, [string]$b) {
    $body = JsonBody @{ roomTypeId = $RT; checkIn = $a; checkOut = $b; roomCount = 1 }
    return Api POST '/booking/quote' $body
}
function NightPrice($resp, [string]$date) {
    foreach ($n in $resp.Body.data.nightPrices) {
        if ("$($n.stayDate)" -eq $date) { return [double]$n.price }
    }
    return -1
}
function PriceKey([string]$date) {
    $ym = ([datetime]::Parse($date)).ToString('yyyy-MM')
    return "hotel:price:{rt:$RT}:$ym"
}
# 种子价：工作日 400、周末 600（db/seed-p0.sql 里写死的规则）
# 还原时必须回到这个基准，而不是「运行时刻的当前值」——
# 否则上一次跑留下的污染会被当成基准继续传下去
# （踩过：D101 残留 1234，导致「改前不是 1234」那条断言失败）
function SeedPrice([string]$date) {
    $dw = ([datetime]::Parse($date)).DayOfWeek
    if ($dw -eq 'Saturday' -or $dw -eq 'Sunday') { return 600.0 }
    return 400.0
}

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')    房型 $RT"
"  住宿夜1 = $ci   住宿夜2 = $d2   2晚区间 = $ci ~ $co2   无价格 = $far"

"`n=== 0. 重置（会一并清掉价格缓存）==="
ResetTestData | Out-Null
"  价格缓存键数 = $(((@(RedisCmd @('KEYS','hotel:price:*')) -split "`n") | Where-Object { $_.Trim() }).Count)"

# ---------------------------------------------------------------------------
"`n=== 1. 价格走缓存（第二次报价不再查库）==="
# 冷缓存必须用一个"还没被碰过"的日期区间来量。
# 如果先 Quote 了 $ci 再量，缓存已经热了，就量不到"回源"那一次（踩过）。
$coldIn  = D 110
$coldOut = D 111
$cold = PriceSelectsDuring { Quote $coldIn $coldOut | Out-Null }
$warm = PriceSelectsDuring { Quote $coldIn $coldOut | Out-Null }
"  冷缓存 1 次报价（$coldIn）-> 查 room_price $cold 次"
"  热缓存 1 次报价（$coldIn）-> 查 room_price $warm 次"
Check 'first quote loads prices from DB' ($cold -ge 1) "actual=$cold"
Check 'second quote is served from cache (0 DB queries)' ($warm -eq 0) "actual=$warm"

# 现在再报价 $ci（顺便建立后面要用的缓存）
$q = Quote $ci $co
$p0 = NightPrice $q $ci
"  $ci 的报价 = $p0    HTTP $($q.Http)"
Check 'quote returns a price' ($p0 -gt 0) "actual=$p0"

$key = PriceKey $ci
"  缓存键 = $key   TTL = $(RedisCmd @('TTL',$key)) 秒   $ci 存的值 = [$(RedisCmd @('HGET',$key,$ci))]"
Check 'price cache key exists' ((RedisCmd @('TTL',$key)) -gt 0) "ttl=$(RedisCmd @('TTL',$key))"
Check 'cached value equals the quoted price' ([double](RedisCmd @('HGET',$key,$ci)) -eq $p0) ''

# ---------------------------------------------------------------------------
"`n=== 2. ★ 改价 -> 缓存失效 -> 报价立刻变 ==="
$newPrice = $p0 + 777
$upd = Api POST '/booking/hotel/price/update' (JsonBody @{ roomTypeId = $RT; stayDate = $ci; price = $newPrice })
"  POST /hotel/price/update -> HTTP $($upd.Http)"
"    oldPrice=$($upd.Body.data.oldPrice)  newPrice=$($upd.Body.data.newPrice)  cacheInvalidated=$($upd.Body.data.cacheInvalidated)"
Check 'price update succeeded' ($upd.Http -eq 200) ''
Check 'it reports the old price' ([double]$upd.Body.data.oldPrice -eq $p0) "actual=$($upd.Body.data.oldPrice)"
Check 'it reports that the cache was invalidated' ($upd.Body.data.cacheInvalidated -eq $true) ''

"  改价后那天在缓存里还有吗: [$(RedisCmd @('HGET',$key,$ci))]（空 = 已失效）"
Check 'the cached value is gone after the update' ((RedisCmd @('HGET',$key,$ci)) -eq '') ''

$q2 = Quote $ci $co
$p1 = NightPrice $q2 $ci
"  改价后报价 -> $ci 的价格 = $p1  （应为 $newPrice）"
Check 'the next quote returns the NEW price' ($p1 -eq $newPrice) "expected=$newPrice actual=$p1"

# ---------------------------------------------------------------------------
"`n=== 3. 只影响那一天 ==="
$q3 = Quote $ci $co2
$d2Before = NightPrice $q3 $d2
"  2 晚报价: $ci=$((NightPrice $q3 $ci))  $d2=$d2Before"
Check 'the 2-night quote contains both nights' (($d2Before -gt 0) -and ((NightPrice $q3 $ci) -eq $newPrice)) ''

$upd2 = Api POST '/booking/hotel/price/update' (JsonBody @{ roomTypeId = $RT; stayDate = $d2; price = 1234.00 })
$q4 = Quote $ci $co2
"  改 $d2 之后: $ci=$(NightPrice $q4 $ci)  $d2=$(NightPrice $q4 $d2)"
Check 'the second night now shows its own new price' ((NightPrice $q4 $d2) -eq 1234.0) "actual=$(NightPrice $q4 $d2)"
Check 'updating the second night does not touch the first' ((NightPrice $q4 $ci) -eq $newPrice) ''
Check 'the second night started from the seed price' ($d2Before -eq (SeedPrice $d2)) "before=$d2Before seed=$(SeedPrice $d2)"

# ---------------------------------------------------------------------------
"`n=== 4. 改价真的写进了数据库 ==="
$dbNew = Sql "SELECT price FROM room_price WHERE room_type_id=$RT AND stay_date='$ci'"
$dbD2  = Sql "SELECT price FROM room_price WHERE room_type_id=$RT AND stay_date='$d2'"
"  DB: $ci = $dbNew    $d2 = $dbD2"
Check 'DB holds the new price for the first night' ([double]$dbNew -eq $newPrice) "db=$dbNew"
Check 'DB holds the new price for the second night' ([double]$dbD2 -eq 1234.0) "db=$dbD2"

# ---------------------------------------------------------------------------
"`n=== 5. 没有价格日历的日期：409 + 缓存记占位符 + 不再查库 ==="
$noPrice = Quote $far (D 201)
"  报价 $far -> HTTP $($noPrice.Http)  msg=$($noPrice.Body.message)"
Check 'a date without a price calendar -> 409' ($noPrice.Http -eq 409) "actual=$($noPrice.Http)"

$farKey = PriceKey $far
$farVal = RedisCmd @('HGET', $farKey, $far)
"  缓存里 $far = [$farVal]  （应为减号，表示查过了、确实没有）"
Check 'the absence is cached as a marker' ($farVal -eq '-') "actual=[$farVal]"

$repeat = PriceSelectsDuring { Quote $far (D 201) | Out-Null }
"  再报一次同样的无价格日期 -> 查 room_price $repeat 次（应为 0，否则就是穿透）"
Check 'repeating it does not hit the DB again' ($repeat -eq 0) "actual=$repeat"

# ---------------------------------------------------------------------------
"`n=== 6. 缓存键结构与 TTL ==="
$keys = @((@(RedisCmd @('KEYS','hotel:price:*')) -split "`n") | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })
"  当前价格缓存键（$($keys.Count) 个）:"
foreach ($k in $keys) { "    {0,-38} TTL={1}s" -f $k, (RedisCmd @('TTL',$k)) }
Check 'keys are shaped hotel:price:{rt:N}:yyyy-MM' (@($keys | Where-Object { $_ -match '^hotel:price:\{rt:\d+\}:\d{4}-\d{2}$' }).Count -eq $keys.Count) ''
Check 'no price key is permanent' (@($keys | Where-Object { [int](RedisCmd @('TTL',$_)) -le 0 }).Count -eq 0) ''

# ---------------------------------------------------------------------------
#  ★ 收尾：把改过的价格还原
#
#  这个用例会真的改 room_price（不然没法验证"改价 -> 缓存失效"）。
#  改完必须复原 —— 否则种子数据被污染，以后别的用例用到同一天、
#  期待 400 却读到 1954，就成了一条谁也看不懂的假失败。
#  （P2 那次"故意污染缓存却没修回来"就是这么翻车的）
"`n=== 7. 还原测试改过的价格（回到种子价）==="
$seed1 = SeedPrice $ci
$seed2 = SeedPrice $d2
Api POST '/booking/hotel/price/update' (JsonBody @{ roomTypeId = $RT; stayDate = $ci; price = $seed1 }) | Out-Null
Api POST '/booking/hotel/price/update' (JsonBody @{ roomTypeId = $RT; stayDate = $d2; price = $seed2 }) | Out-Null
$back1 = Sql "SELECT price FROM room_price WHERE room_type_id=$RT AND stay_date='$ci'"
$back2 = Sql "SELECT price FROM room_price WHERE room_type_id=$RT AND stay_date='$d2'"
"  $ci 还原为 $back1 （种子价 $seed1）    $d2 还原为 $back2 （种子价 $seed2）"
Check 'first night restored to the seed price' ([double]$back1 -eq $seed1) "db=$back1 seed=$seed1"
Check 'second night restored to the seed price' ([double]$back2 -eq $seed2) "db=$back2 seed=$seed2"

Summarize
