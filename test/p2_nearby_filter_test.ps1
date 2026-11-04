# =============================================================================
#  p2_nearby_filter_test.ps1 —— 附近酒店：排序 + 有房过滤（P2.3）
#
#  验证四件事：
#   1) 三种排序各自生效（distance / rating / price）
#   2) ★ 排序与截断的顺序：limit=2 + 按价格排，必须返回"最便宜的 2 家"，
#      而不是"最近的 2 家里挑最便宜的"
#   3) 有房过滤：把某家酒店的所有房型占满，它就消失；不传日期它就回来
#   4) 叠加：过滤之后再排序
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$C_LNG = 120.14
$C_LAT = 30.2489

$ci = D 0
$co = D 1

function Nearby2([string]$extra) {
    return Api GET "/booking/hotel/nearby?longitude=$C_LNG&latitude=$C_LAT&radiusKm=10&limit=20&$extra" ''
}
function Ids($resp) { ($resp.Body.data.hotels | ForEach-Object { "$($_.hotelId)" }) -join ',' }
function Scores($resp) { ($resp.Body.data.hotels | ForEach-Object { "$($_.score)" }) -join ',' }
function Prices($resp) { ($resp.Body.data.hotels | ForEach-Object { "$($_.minPrice)" }) -join ',' }
function Dists($resp)  { ($resp.Body.data.hotels | ForEach-Object { "$($_.distanceKm)" }) -join ',' }

function IsAscending($resp, [string]$field) {
    $prev = [double]::MinValue
    $ok = $true
    foreach ($h in $resp.Body.data.hotels) {
        $v = [double]$h.$field
        if ($v -lt $prev) { $ok = $false }
        $prev = $v
    }
    return $ok
}
function IsDescending($resp, [string]$field) {
    $prev = [double]::MaxValue
    $ok = $true
    foreach ($h in $resp.Body.data.hotels) {
        $v = [double]$h.$field
        if ($v -gt $prev) { $ok = $false }
        $prev = $v
    }
    return $ok
}

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')    圆心 = ($C_LNG, $C_LAT)    过滤区间 = $ci ~ $co"

"`n=== 0. 重置 + 重建 GEO 索引 ==="
ResetTestData | Out-Null
Api POST '/booking/hotel/geo/rebuild' '' | Out-Null
"  bookings=$(BCount)  GEO size=$(RedisCmd @('ZCARD','hotel:geo:all'))"

# ---------------------------------------------------------------------------
"`n=== 1. 三种排序 ==="
$d = Nearby2 'sortBy=distance'
$r = Nearby2 'sortBy=rating'
$p = Nearby2 'sortBy=price'
"  distance -> ids=[$(Ids $d)]"
"              dists=[$(Dists $d)]"
"  rating   -> ids=[$(Ids $r)]  scores=[$(Scores $r)]"
"  price    -> ids=[$(Ids $p)]  prices=[$(Prices $p)]"
Check 'distance sort gives the GEO order'   ((Ids $d) -eq '1001,1002,1003,1005,1004') ''
Check 'distance sort is ascending'          (IsAscending $d 'distanceKm') ''
Check 'rating sort is descending'           (IsDescending $r 'score') ''
Check 'rating sort order is correct'        ((Ids $r) -eq '1002,1001,1005,1003,1004') ''
Check 'price sort is ascending'             (IsAscending $p 'minPrice') ''
Check 'price sort order is correct (ties broken by distance)' ((Ids $p) -eq '1004,1002,1001,1003,1005') ''
Check 'default sortBy is distance'          ((Ids (Nearby2 '')) -eq (Ids $d)) ''

$bad = Nearby2 'sortBy=cheapest'
"  sortBy=cheapest -> HTTP $($bad.Http)  msg=$($bad.Body.message)"
Check 'invalid sortBy -> 400' ($bad.Http -eq 400) ''

# ---------------------------------------------------------------------------
"`n=== 2. ★ 排序 + 截断的顺序（这一条最容易写错）==="
"  如果实现是「先按距离取 limit 个、再排序」，limit=2+price 会给出最近的 2 家再排价格，"
"  也就是 [1002,1001]；正确答案是【全范围内最便宜的 2 家】= [1004,1002]。"
$p2 = Api GET "/booking/hotel/nearby?longitude=$C_LNG&latitude=$C_LAT&radiusKm=10&limit=2&sortBy=price" ''
$d2 = Api GET "/booking/hotel/nearby?longitude=$C_LNG&latitude=$C_LAT&radiusKm=10&limit=2&sortBy=distance" ''
"  limit=2 & sortBy=price    -> ids=[$(Ids $p2)]  （应为最便宜的 1004,1002）"
"  limit=2 & sortBy=distance -> ids=[$(Ids $d2)]  （应为最近的 1001,1002）"
Check 'price+limit=2 returns the 2 CHEAPEST, not the 2 nearest' ((Ids $p2) -eq '1004,1002') ''
Check 'distance+limit=2 returns the 2 nearest'                  ((Ids $d2) -eq '1001,1002') ''
Check 'the two cases differ (proves the candidate widening)'    ((Ids $p2) -ne (Ids $d2)) ''

"`n  -- count vs matchedTotal --"
"  count=$($p2.Body.data.count)  matchedTotal=$($p2.Body.data.matchedTotal)  sortBy=$($p2.Body.data.sortBy)"
Check 'count is truncated to limit'   ($p2.Body.data.count -eq 2) ''
Check 'matchedTotal keeps the full match count' ($p2.Body.data.matchedTotal -eq 5) ''

# ---------------------------------------------------------------------------
"`n=== 3. 有房过滤 ==="
"  把 hotel 1001 的两个房型都占满：2001 订 3 间 / 2002 订 2 间（$ci ~ $co）"
$b1 = NewBooking 2001 $ci $co 3
$b2 = NewBooking 2002 $ci $co 2
"    2001 -> HTTP $($b1.Http)   2002 -> HTTP $($b2.Http)"
"    1001 的剩余：2001=$(Sql "SELECT IFNULL(total_rooms-sold_rooms,-1) FROM room_inventory WHERE room_type_id=2001 AND stay_date='$ci'")  2002=$(Sql "SELECT IFNULL(total_rooms-sold_rooms,-1) FROM room_inventory WHERE room_type_id=2002 AND stay_date='$ci'")"

$noFilter = Nearby2 ''
$filtered = Api GET "/booking/hotel/nearby?longitude=$C_LNG&latitude=$C_LAT&radiusKm=10&limit=20&checkIn=$ci&checkOut=$co" ''
"  不传日期   -> ids=[$(Ids $noFilter)]  filtered=$($noFilter.Body.data.availabilityFiltered)"
"  传了日期   -> ids=[$(Ids $filtered)]  filtered=$($filtered.Body.data.availabilityFiltered)"
Check 'without dates the hotel is still listed' ((Ids $noFilter) -like '*1001*') ''
Check 'with dates the sold-out hotel is filtered out' ((Ids $filtered) -notlike '*1001*') ''
Check 'the other 4 hotels remain' ((Ids $filtered) -eq '1002,1003,1005,1004') ''
Check 'availabilityFiltered reflects the request' ($filtered.Body.data.availabilityFiltered -eq $true) ''

"`n  -- 只传一个日期要报错（不能让「半个条件」被静默忽略）--"
$half = Api GET "/booking/hotel/nearby?longitude=$C_LNG&latitude=$C_LAT&checkIn=$ci" ''
"  只传 checkIn -> HTTP $($half.Http)  msg=$($half.Body.message)"
Check 'half a date range -> 400' ($half.Http -eq 400) ''

$badRange = Api GET "/booking/hotel/nearby?longitude=$C_LNG&latitude=$C_LAT&checkIn=$co&checkOut=$ci" ''
"  checkIn > checkOut -> HTTP $($badRange.Http)  msg=$($badRange.Body.message)"
Check 'reversed date range -> 400' ($badRange.Http -eq 400) ''

# ---------------------------------------------------------------------------
"`n=== 4. 过滤 + 排序叠加 ==="
$fp = Api GET "/booking/hotel/nearby?longitude=$C_LNG&latitude=$C_LAT&radiusKm=10&limit=20&checkIn=$ci&checkOut=$co&sortBy=price" ''
"  filter + sortBy=price -> ids=[$(Ids $fp)]  prices=[$(Prices $fp)]"
Check 'filtered list is price-ascending' (IsAscending $fp 'minPrice') ''
Check 'filtered+price order is correct'  ((Ids $fp) -eq '1004,1002,1003,1005') "actual=$(Ids $fp)"

# ---------------------------------------------------------------------------
"`n=== 5. 只占满一个房型不影响酒店可订（「任一房型可订」即可）==="
"  先取消掉 2002 的那单，让 1001 只剩 2001 被占满 -> 应该又能出现了"
Api POST "/booking/$($b2.Body.data)/cancel" '{"reason":"test","cancelRole":1}' | Out-Null
$again = Api GET "/booking/hotel/nearby?longitude=$C_LNG&latitude=$C_LAT&radiusKm=10&limit=20&checkIn=$ci&checkOut=$co" ''
"  取消 2002 后 -> ids=[$(Ids $again)]"
Check 'hotel 1001 is back (its 2002 room type has vacancy)' ((Ids $again) -like '*1001*') ''

"`n  -- 再把 2001 也取消，恢复干净状态 --"
Api POST "/booking/$($b1.Body.data)/cancel" '{"reason":"test","cancelRole":1}' | Out-Null
"  bookings=$(BCount)"

# ---------------------------------------------------------------------------
"`n=== 6. roomCount 参与过滤（两条独立路径）==="
$nbQ = "/booking/hotel/nearby?longitude=$C_LNG&latitude=$C_LAT&radiusKm=10&limit=20&checkIn=$ci&checkOut=$co"
$rc1 = Api GET "$nbQ&roomCount=1" ''
$rc3 = Api GET "$nbQ&roomCount=3" ''
"  1004 的房型 2005 总共只有 2 间："
"    roomCount=1 -> ids=[$(Ids $rc1)]"
"    roomCount=3 -> ids=[$(Ids $rc3)]"
Check 'roomCount=1 keeps 1004' ((Ids $rc1) -like '*1004*') ''
Check 'roomCount=3 excludes 1004 (only 2 rooms exist in total)' ((Ids $rc3) -notlike '*1004*') ''

"`n  -- 路径二：把 2005 的两间真的订满（注意 hotelId 要传 1004）--"
$b3 = NewBooking 2005 $ci $co 2 1004
"    2005 订 2 间 -> HTTP $($b3.Http)"
Check 'booking a non-1001 hotel works when hotelId is passed' ($b3.Http -eq 200) "HTTP $($b3.Http)"
$rc2 = Api GET "$nbQ&roomCount=2" ''
"    roomCount=2 -> ids=[$(Ids $rc2)]   （1004 应消失：2 间被占满）"
Check 'roomCount=2 excludes 1004 once fully booked' ((Ids $rc2) -notlike '*1004*') ''
Check 'the other hotels are unaffected' (((Ids $rc2) -like '*1001*') -and ((Ids $rc2) -like '*1005*')) ''

Summarize
