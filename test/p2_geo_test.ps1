# =============================================================================
#  p2_geo_test.ps1 —— 酒店 GEO 索引 + 附近查询（P2.2）
#
#  验证五件事：
#   1) 索引为空时的行为（indexedHotels=0，不能和"附近没有酒店"混为一谈）
#   2) 从 DB 重建索引
#   3) 半径过滤正确（1km / 5km / 10km 三档，进出边界不同）
#   4) ★ 距离数值正确 —— 用独立的 haversine 公式验算 API 返回的 distanceKm
#   5) 排序、limit、参数校验、索引丢失后能重建
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$GEO = 'hotel:geo:all'

# 以 1001（杭州西湖畔精选酒店）为圆心
$C_LNG = 120.14
$C_LAT = 30.2489

# 独立实现的球面距离（Redis GEO 内部用的是 haversine，地球半径取 6372797.56 m；
# 这里用 6371.0088 km，差 0.03%，在几公里尺度上只差几米）
function HaversineKm([double]$lat1, [double]$lng1, [double]$lat2, [double]$lng2) {
    $R = 6371.0088
    $rad = [Math]::PI / 180.0
    $dLat = ($lat2 - $lat1) * $rad
    $dLng = ($lng2 - $lng1) * $rad
    $a = [Math]::Sin($dLat / 2) * [Math]::Sin($dLat / 2) +
         [Math]::Cos($lat1 * $rad) * [Math]::Cos($lat2 * $rad) *
         [Math]::Sin($dLng / 2) * [Math]::Sin($dLng / 2)
    $c = 2 * [Math]::Atan2([Math]::Sqrt($a), [Math]::Sqrt(1 - $a))
    return $R * $c
}

function Nearby([double]$lng, [double]$lat, [double]$r, [int]$limit) {
    return Api GET "/booking/hotel/nearby?longitude=$lng&latitude=$lat&radiusKm=$r&limit=$limit" ''
}
function Ids($resp) { ($resp.Body.data.hotels | ForEach-Object { "$($_.hotelId)" }) -join ',' }

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')    圆心 = ($C_LNG, $C_LAT)（酒店 1001）"

"`n=== 0. 重置（会一并清掉 GEO 索引）==="
$nk = ResetTestData
"  DB bookings=$(BCount)   Redis 清掉 $nk 个键   索引 size=$(RedisCmd @('ZCARD', $GEO))"

# ---------------------------------------------------------------------------
"`n=== 1. 索引为空时：要能和「附近没有酒店」区分开 ==="
$e = Nearby $C_LNG $C_LAT 10 20
"  HTTP $($e.Http)  count=$($e.Body.data.count)  indexedHotels=$($e.Body.data.indexedHotels)"
Check 'empty index returns count=0' ($e.Body.data.count -eq 0) ''
Check 'empty index is visible via indexedHotels=0' ($e.Body.data.indexedHotels -eq 0) 'this is the hint for callers'

# ---------------------------------------------------------------------------
"`n=== 2. 从 DB 重建索引 ==="
$rb = Api POST '/booking/hotel/geo/rebuild' ''
"  HTTP $($rb.Http)  indexed=$($rb.Body.data.indexed)  size=$($rb.Body.data.size)"
$dbHotels = [int](Sql "SELECT COUNT(*) FROM hotel WHERE status=1")
"  DB 里上架酒店数 = $dbHotels"
Check 'rebuild indexed every active hotel' ($rb.Body.data.indexed -eq $dbHotels) "indexed=$($rb.Body.data.indexed)"
Check 'Redis GEO key size matches' ((RedisCmd @('ZCARD', $GEO)) -eq "$dbHotels") ''

"`n  -- geo/stat --"
$st = Api GET '/booking/hotel/geo/stat' ''
"  key=$($st.Body.data.key)  size=$($st.Body.data.size)"
Check 'geo/stat reports the same size' ($st.Body.data.size -eq $dbHotels) ''

# ---------------------------------------------------------------------------
"`n=== 3. 半径过滤（1km / 5km / 10km）==="
$r1  = Nearby $C_LNG $C_LAT 1 20
$r5  = Nearby $C_LNG $C_LAT 5 20
$r10 = Nearby $C_LNG $C_LAT 10 20
"  1 km  -> count=$($r1.Body.data.count)   ids=[$(Ids $r1)]"
"  5 km  -> count=$($r5.Body.data.count)   ids=[$(Ids $r5)]"
"  10 km -> count=$($r10.Body.data.count)  ids=[$(Ids $r10)]"
Check '1km keeps only hotel 1001'      ((Ids $r1) -eq '1001') ''
Check '5km keeps 1001,1002,1003'       ((Ids $r5) -eq '1001,1002,1003') ''
Check '10km keeps all 5'               (($r10.Body.data.hotels).Count -eq 5) ''
Check 'widening radius never shrinks the set' (($r1.Body.data.count -le $r5.Body.data.count) -and ($r5.Body.data.count -le $r10.Body.data.count)) ''

# ---------------------------------------------------------------------------
"`n=== 4. ★ 距离数值用独立公式验算 ==="
$bad = 0
foreach ($h in $r10.Body.data.hotels) {
    $lng = [double]$h.longitude
    $lat = [double]$h.latitude
    $expect = HaversineKm $C_LAT $C_LNG $lat $lng
    $got = [double]$h.distanceKm
    $diff = [Math]::Abs($expect - $got)
    $flag = if ($diff -le 0.05) { 'OK ' } else { 'BAD'; }
    if ($flag -eq 'BAD') { $bad++ }
    "  [{0}] hotel {1,-5} API={2,7:N3} km  haversine={3,7:N3} km  差={4:N4} km" -f $flag, $h.hotelId, $got, $expect, $diff
}
Check 'every distance matches haversine within 50 m' ($bad -eq 0) "mismatched=$bad"

# ---------------------------------------------------------------------------
"`n=== 5. 排序：距离升序 ==="
$prev = -1.0
$ordered = $true
foreach ($h in $r10.Body.data.hotels) {
    $d = [double]$h.distanceKm
    if ($d -lt $prev) { $ordered = $false }
    $prev = $d
}
Check 'results are sorted by distance ascending' $ordered ''
"  最近的是 hotel $($r10.Body.data.hotels[0].hotelId)（$($r10.Body.data.hotels[0].distanceKm) km）"
Check 'nearest is 1001 (the query centre itself)' ("$($r10.Body.data.hotels[0].hotelId)" -eq '1001') ''

# ---------------------------------------------------------------------------
"`n=== 6. limit 生效 ==="
$lim = Nearby $C_LNG $C_LAT 10 2
"  limit=2 -> count=$($lim.Body.data.count)  ids=[$(Ids $lim)]"
Check 'limit=2 returns only 2' ($lim.Body.data.count -eq 2) ''
Check 'limit keeps the nearest ones' ((Ids $lim) -eq '1001,1002') ''

# ---------------------------------------------------------------------------
"`n=== 7. 边界与参数校验 ==="
$tiny = Nearby $C_LNG $C_LAT 0.01 20
"  radius=0.01km -> count=$($tiny.Body.data.count)  ids=[$(Ids $tiny)]  （圆心正好是 1001 的坐标，距离 0.000 km）"
Check 'tiny radius still finds the hotel at the exact centre' ((Ids $tiny) -eq '1001') "actual=$(Ids $tiny)"

$noLng = Api GET '/booking/hotel/nearby?latitude=30.2489' ''
"  缺 longitude -> HTTP $($noLng.Http)  msg=$($noLng.Body.message)"
Check 'missing longitude -> 400' ($noLng.Http -eq 400) ''

$badLng = Api GET '/booking/hotel/nearby?longitude=200&latitude=30.2489' ''
"  longitude=200 -> HTTP $($badLng.Http)  msg=$($badLng.Body.message)"
Check 'out-of-range longitude -> 400' ($badLng.Http -eq 400) ''

$badLat = Api GET '/booking/hotel/nearby?longitude=120.14&latitude=99' ''
"  latitude=99 -> HTTP $($badLat.Http)  msg=$($badLat.Body.message)"
Check 'out-of-range latitude -> 400' ($badLat.Http -eq 400) ''

$badLim = Api GET '/booking/hotel/nearby?longitude=120.14&latitude=30.2489&limit=999' ''
"  limit=999 -> HTTP $($badLim.Http)  msg=$($badLim.Body.message)"
Check 'limit above 50 -> 400' ($badLim.Http -eq 400) ''

# ---------------------------------------------------------------------------
"`n=== 8. 索引丢了能重建 ==="
"  DEL $GEO"
RedisCmd @('DEL', $GEO) | Out-Null
$gone = Nearby $C_LNG $C_LAT 10 20
"  删掉后 -> count=$($gone.Body.data.count)  indexedHotels=$($gone.Body.data.indexedHotels)"
Check 'index loss is visible' ($gone.Body.data.indexedHotels -eq 0) ''

Api POST '/booking/hotel/geo/rebuild' '' | Out-Null
$back = Nearby $C_LNG $C_LAT 10 20
"  重建后 -> count=$($back.Body.data.count)  indexedHotels=$($back.Body.data.indexedHotels)"
Check 'rebuild restores the whole result' ($back.Body.data.count -eq 5) ''
Check 'restored distances still correct' ([double]($back.Body.data.hotels[1].distanceKm) -gt 1.0) ''

# ---------------------------------------------------------------------------
"`n=== 9. 索引与 DB 一致性 ==="
"  Redis GEO 成员数 = $(RedisCmd @('ZCARD', $GEO))   DB 上架酒店数 = $dbHotels"
Check 'index size == active hotel count' ((RedisCmd @('ZCARD', $GEO)) -eq "$dbHotels") ''

Summarize
