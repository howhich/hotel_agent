# =============================================================================
#  p3_absent_cache_test.ps1 —— 防缓存穿透（P3.1）
#
#  验证五件事：
#   1) 查不存在的订单号 -> 404，并留下「不存在」标记（TTL 很短）
#   2) 拿不存在的房型下单 -> 404，同样留下标记
#   3) ★ 标记存在期间，同样的请求【不再查库】
#   4) 真实数据完全不受影响（该 200 的 200，该能订的能订）
#   5) 标记删掉/过期后能自愈（又去查库、又 404、又记一遍）
#
#  ★ 关于怎么证明"没查库"
#    不能用 Com_select 的绝对值 —— 实测它里面混着 JDBC 驱动的
#    `SELECT @@session.transaction_read_only`（每个请求 1~2 条），
#    还有 SHOW 语句自身的影响。所以这里用【相对比较】：
#        有标记时连发 10 次   -> delta_with
#        每次发之前删掉标记   -> delta_without
#    两者的差 ≈ 10 就说明标记确实省下了 10 次查询。
#    这样无论驱动噪声有多少，结论都不受影响。
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$fakeBooking  = 777777777777777777
$fakeRoomType = 999999
$fakeHotelId  = 88888

$ci  = D 30
$co  = D 32
$ci2 = D 40
$co2 = D 41

function Stat([string]$name) {
    $v = Sql "SHOW GLOBAL STATUS LIKE '$name'"
    if (-not $v) { return [int64]0 }
    return [int64](($v -split "`t")[1])
}
function Marker([string]$key) { RedisCmd @('GET', $key) }
function TtlOf([string]$key) { return [int](RedisCmd @('TTL', $key)) }
function Forget([string]$key) { RedisCmd @('DEL', $key) | Out-Null }

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')"

"`n=== 0. 重置 ==="
ResetTestData | Out-Null
"  bookings=$(BCount)   残留标记数=$(($(RedisCmd @('KEYS','hotel:absent:*')) -split "`n" | Where-Object { $_.Trim() }).Count)"

# ---------------------------------------------------------------------------
"`n=== 1. 刷不存在的订单号 ==="
$kB = "hotel:absent:booking:$fakeBooking"
Forget $kB
$resp = Api GET "/booking/$fakeBooking" ''
"  第 1 次 GET /booking/$fakeBooking -> HTTP $($resp.Http)"
Check 'non-existent booking -> 404' ($resp.Http -eq 404) "actual=$($resp.Http)"
$mk = Marker $kB
$ttl = TtlOf $kB
"  标记 = [$mk]   TTL = $ttl 秒"
Check 'tombstone is written' ($mk -eq '1') "marker=[$mk]"
Check 'tombstone TTL is short (0 < ttl <= 60)' ($ttl -gt 0 -and $ttl -le 60) "ttl=$ttl"

"`n  -- 相对计数：有标记 vs 每次删标记 --"
$s0 = Stat 'Com_select'
1..10 | ForEach-Object { Api GET "/booking/$fakeBooking" '' | Out-Null }
$s1 = Stat 'Com_select'
$deltaWith = $s1 - $s0

$s2 = Stat 'Com_select'
1..10 | ForEach-Object {
    Forget $kB
    Api GET "/booking/$fakeBooking" '' | Out-Null
}
$s3 = Stat 'Com_select'
$deltaWithout = $s3 - $s2

"  有标记时连发 10 次      -> Com_select +$deltaWith"
"  每次先删标记再发 10 次  -> Com_select +$deltaWithout"
"  节省了 $($deltaWithout - $deltaWith) 次查询"
Check 'tombstone saves about 10 SELECTs over 10 requests' (($deltaWithout - $deltaWith) -ge 8) "with=$deltaWith without=$deltaWithout"

# ---------------------------------------------------------------------------
"`n=== 2. 拿不存在的房型下单 ==="
$kR = "hotel:absent:roomtype:$fakeRoomType"
Forget $kR
$body = JsonBody @{
    userId     = 9001
    hotelId    = 1001
    roomTypeId = $fakeRoomType
    checkIn    = $ci
    checkOut   = $co
    roomCount  = 1
    guestCount = 1
}
$resp2 = Api POST '/booking' $body
"  第 1 次 POST /booking（roomTypeId=$fakeRoomType）-> HTTP $($resp2.Http)  msg=$($resp2.Body.message)"
Check 'bogus roomType -> 404' ($resp2.Http -eq 404) "actual=$($resp2.Http)"
$mk2 = Marker $kR
$ttl2 = TtlOf $kR
"  标记 = [$mk2]   TTL = $ttl2 秒"
Check 'room type tombstone is written' ($mk2 -eq '1') "marker=[$mk2]"
Check 'room type tombstone TTL is short' ($ttl2 -gt 0 -and $ttl2 -le 60) "ttl=$ttl2"

"`n  -- 相对计数（注意：这里每次都还有幂等记录的 INSERT/DELETE，只看 SELECT 相对差）--"
$t0 = Stat 'Com_select'
1..10 | ForEach-Object { Api POST '/booking' $body | Out-Null }
$t1 = Stat 'Com_select'
$deltaWith2 = $t1 - $t0

$t2 = Stat 'Com_select'
1..10 | ForEach-Object {
    Forget $kR
    Api POST '/booking' $body | Out-Null
}
$t3 = Stat 'Com_select'
$deltaWithout2 = $t3 - $t2

"  有标记时连发 10 次      -> Com_select +$deltaWith2"
"  每次先删标记再发 10 次  -> Com_select +$deltaWithout2"
"  节省了 $($deltaWithout2 - $deltaWith2) 次查询"
Check 'room type tombstone also saves ~10 SELECTs' (($deltaWithout2 - $deltaWith2) -ge 8) "with=$deltaWith2 without=$deltaWithout2"

# ---------------------------------------------------------------------------
"`n=== 3. 真实数据完全不受影响 ==="
$c = NewBooking 2001 $ci $co 1
"  正常下单 -> HTTP $($c.Http)  bookingId=$($c.Body.data)"
Check 'real booking still succeeds' ($c.Http -eq 200) ''
$b = $c.Body.data
$d = Api GET "/booking/$b" ''
"  查这笔真订单 -> HTTP $($d.Http)  hotelName=$($d.Body.data.hotelName)"
Check 'real booking detail returns 200' ($d.Http -eq 200) ''
Check 'real booking does NOT get marked absent' ((Marker "hotel:absent:booking:$b") -eq '') ''

"  房型 999999 被标记了，但 2001 必须照样能订（标记是按 id 独立的）"
$c2 = NewBooking 2001 $ci2 $co2 1
Check 'a marked room type does not affect other room types' ($c2.Http -eq 200) "actual=$($c2.Http)"
Check 'roomType 2001 is not marked absent' ((Marker 'hotel:absent:roomtype:2001') -eq '') ''

# ---------------------------------------------------------------------------
"`n=== 4. 自愈：标记删掉后重新查库、重新记一遍 ==="
Forget $kB
$u0 = Stat 'Com_select'
$resp3 = Api GET "/booking/$fakeBooking" ''
$u1 = Stat 'Com_select'
"  删标记后再查 -> HTTP $($resp3.Http)   Com_select +$($u1-$u0)  （应 >0，说明真的又查库了）"
Check 'after forgetting, it hits the DB again' (($u1 - $u0) -ge 1) "delta=$($u1-$u0)"
Check 'and re-marks it' ((Marker $kB) -eq '1') ''
Check 'still 404 (not 500)' ($resp3.Http -eq 404) "actual=$($resp3.Http)"

"`n  -- TTL 到期后也会自愈（等价于：这条数据将来真的被创建了，60 秒后就能查到）--"
"  当前 TTL=$((TtlOf $kB)) 秒，到期后标记自动消失，不需要人工清缓存"

# ---------------------------------------------------------------------------
"`n=== 5. 订单引用了已不存在的酒店 ==="
$c3 = NewBooking 2001 (D 50) (D 51) 1
$b3 = $c3.Body.data
Sql "UPDATE booking SET hotel_id = $fakeHotelId WHERE booking_id=$b3" | Out-Null
Forget "hotel:absent:hotel:$fakeHotelId"
$d3 = Api GET "/booking/$b3" ''
"  HTTP $($d3.Http)  hotelName=[$($d3.Body.data.hotelName)]  roomTypeName=[$($d3.Body.data.roomTypeName)]"
Check 'detail still returns 200 with a missing hotel' ($d3.Http -eq 200) ''
Check 'hotelName degrades to null' ($null -eq $d3.Body.data.hotelName) ''
Check 'missing hotel is marked absent' ((Marker "hotel:absent:hotel:$fakeHotelId") -eq '1') ''

# ---------------------------------------------------------------------------
"`n=== 6. 收尾检查 ==="
$allMarks = (RedisCmd @('KEYS','hotel:absent:*')) -split "`n" | Where-Object { $_.Trim() }
"  当前存在的标记（$($allMarks.Count) 个）:"
$allMarks | ForEach-Object { "    {0}  (TTL={1}s)" -f $_.Trim(), (TtlOf $_.Trim()) }
Check 'every marker has a TTL (none is permanent)' (@($allMarks | Where-Object { (TtlOf $_.Trim()) -le 0 }).Count -eq 0) ''

Summarize
