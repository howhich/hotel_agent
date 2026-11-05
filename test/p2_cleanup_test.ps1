# =============================================================================
#  p2_cleanup_test.ps1 —— 定期清理（P2.4）
#
#  验证三件事：
#   1) 过期幂等记录会被清掉，没过期的不能被清（别把还在窗口内的删了）
#   2) 延迟队列里的残留成员（订单已终态 / 不存在 / 非法）会被清掉，
#      而"还处于待支付"的有效任务必须保留
#   3) 清理是幂等的：连跑两次，第二次什么都不删
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$Q = 'hotel:delay:order'
$ci = D 0
$co = D 1
$ci2 = D 2
$co2 = D 3
$ci3 = D 4
$co3 = D 5

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')"

"`n=== 0. 重置 ==="
ResetTestData | Out-Null
"  bookings=$(BCount)  幂等记录=$(Sql 'SELECT COUNT(*) FROM idempotent_record')  队列深度=$(RedisCmd @('ZCARD',$Q))"

# ---------------------------------------------------------------------------
"`n=== 1. 幂等记录：过期的清掉，没过期的不许动 ==="
$c1 = NewBooking 2001 $ci $co 1
$b1 = $c1.Body.data
$c2 = NewBooking 2001 $ci2 $co2 1
$b2 = $c2.Body.data
"  下了 2 单 -> 幂等记录 $(Sql 'SELECT COUNT(*) FROM idempotent_record') 条"

"  把 b1 那条的 expire_time 改到 1 小时前（另一条保持未来）"
Sql "UPDATE idempotent_record SET expire_time = NOW() - INTERVAL 1 HOUR WHERE result_ref = '$b1'" | Out-Null
"    过期=$(Sql 'SELECT COUNT(*) FROM idempotent_record WHERE expire_time < NOW()')  未过期=$(Sql 'SELECT COUNT(*) FROM idempotent_record WHERE expire_time >= NOW()')"

$st = Api GET '/booking/maintenance/stat' ''
"  GET /maintenance/stat -> idempotentRecords=$($st.Body.data.idempotentRecords)  expired=$($st.Body.data.expiredIdempotentRecords)"

$cl = Api POST '/booking/maintenance/cleanup' ''
"  POST /maintenance/cleanup -> idempotentDeleted=$($cl.Body.data.idempotentDeleted)  delayMembersPurged=$($cl.Body.data.delayMembersPurged)"
$left = Sql "SELECT COUNT(*) FROM idempotent_record"
"  剩余幂等记录 $left 条；b1 的记录还在吗: $(Sql "SELECT COUNT(*) FROM idempotent_record WHERE result_ref='$b1'")"
Check 'expired idempotent record is deleted' ((Sql "SELECT COUNT(*) FROM idempotent_record WHERE result_ref='$b1'") -eq '0') ''
Check 'NOT-yet-expired record is kept'      ((Sql "SELECT COUNT(*) FROM idempotent_record WHERE result_ref='$b2'") -eq '1') ''
Check 'exactly one record was deleted'      ($cl.Body.data.idempotentDeleted -eq 1) "actual=$($cl.Body.data.idempotentDeleted)"

"`n  -- 幂等清理不应影响业务：b2 的重放还得有效 --"
$replay = Api POST '/booking' (JsonBody @{
    userId=9001; hotelId=1001; roomTypeId=2001; checkIn=$ci2; checkOut=$co2; roomCount=1; guestCount=1 })
"  重放 b2 的请求 -> HTTP $($replay.Http)  data=$($replay.Body.data)  （应等于 $b2）"
Check 'replay still works after cleanup' ("$(($replay.Body).data)" -eq "$b2") "actual=$(($replay.Body).data)"

# ---------------------------------------------------------------------------
"`n=== 2. 延迟队列：清残留，保有效 ==="
ResetTestData | Out-Null

# 有效任务：待支付订单
$cA = NewBooking 2001 $ci3 $co3 1
$bA = $cA.Body.data
# 已终态任务：支付掉，然后手工把它塞回队列（模拟"当时 ZREM 没成功"）
$cB = NewBooking 2002 $ci3 $co3 1
$bB = $cB.Body.data
Api POST "/booking/$bB/pay" '{"payChannel":1}' | Out-Null
# 孤儿任务：DB 里根本不存在的 bookingId
$ghost = '900000000000000001'
# 非法任务：成员不是数字
$junk = 'not-a-booking-id'

RedisCmd @('ZADD', $Q, "$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+600000)", "$bB") | Out-Null
RedisCmd @('ZADD', $Q, "$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+600000)", $ghost) | Out-Null
RedisCmd @('ZADD', $Q, "$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+600000)", $junk) | Out-Null

"  队列里现在有 $((RedisCmd @('ZCARD',$Q))) 个成员："
"    待支付(要保留)  $bA"
"    已支付(要清掉)  $bB"
"    孤儿(要清掉)    $ghost"
"    非法(要清掉)    $junk"

$cl2 = Api POST '/booking/maintenance/cleanup' ''
"  POST /maintenance/cleanup -> delayMembersPurged=$($cl2.Body.data.delayMembersPurged)"
"  清理后队列 $((RedisCmd @('ZCARD',$Q))) 个成员"
"    $bA 还在吗: [$(RedisCmd @('ZSCORE',$Q,"$bA"))]"
"    $bB 还在吗: [$(RedisCmd @('ZSCORE',$Q,"$bB"))]"
"    孤儿还在吗: [$(RedisCmd @('ZSCORE',$Q,$ghost))]"
"    非法还在吗: [$(RedisCmd @('ZSCORE',$Q,$junk))]"

Check 'pending booking stays in the queue'  ((RedisCmd @('ZSCORE',$Q,"$bA")) -ne '') ''
Check 'paid booking is purged from the queue' ((RedisCmd @('ZSCORE',$Q,"$bB")) -eq '') ''
Check 'orphan member (no such booking) is purged' ((RedisCmd @('ZSCORE',$Q,$ghost)) -eq '') ''
Check 'non-numeric member is purged' ((RedisCmd @('ZSCORE',$Q,$junk)) -eq '') ''
Check 'purged exactly 3 members' ($cl2.Body.data.delayMembersPurged -eq 3) "actual=$($cl2.Body.data.delayMembersPurged)"

"`n  -- 保留的那单还能正常超时释放（没被清理误伤）--"
$past = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - 5000
RedisCmd @('ZADD', $Q, "$past", "$bA") | Out-Null
$deadline = (Get-Date).AddSeconds(12)
while ((Get-Date) -lt $deadline) {
    if ((Sql "SELECT booking_status FROM booking WHERE booking_id=$bA") -eq '91') { break }
    Start-Sleep -Milliseconds 300
}
$stA = Sql "SELECT booking_status FROM booking WHERE booking_id=$bA"
"    bA 的状态 = $stA   库存 $ci3=$(DSold 2001 $ci3)"
Check 'the preserved booking still expires correctly' ($stA -eq '91') "actual=$stA"
Check 'its inventory was released' ((DSold 2001 $ci3) -eq '0') ''

# ---------------------------------------------------------------------------
"`n=== 3. 清理是幂等的（连跑两次，第二次什么都不删）==="
$cl3 = Api POST '/booking/maintenance/cleanup' ''
"  第一次 -> deleted=$($cl3.Body.data.idempotentDeleted)  purged=$($cl3.Body.data.delayMembersPurged)"
$cl4 = Api POST '/booking/maintenance/cleanup' ''
"  第二次 -> deleted=$($cl4.Body.data.idempotentDeleted)  purged=$($cl4.Body.data.delayMembersPurged)"
Check 'second cleanup deletes nothing' (($cl4.Body.data.idempotentDeleted -eq 0) -and ($cl4.Body.data.delayMembersPurged -eq 0)) ''

# ---------------------------------------------------------------------------
"`n=== 4. 清理后的一致性 ==="
$mismatch = Sql @"
SELECT COUNT(*) FROM room_inventory i
LEFT JOIN (SELECT room_type_id, stay_date, SUM(room_count) n FROM booking_inventory_link WHERE released=0 GROUP BY room_type_id, stay_date) l
  ON l.room_type_id=i.room_type_id AND l.stay_date=i.stay_date
WHERE i.sold_rooms <> IFNULL(l.n,0)
"@
Check 'no drift between ledger and inventory' ($mismatch -eq '0') "mismatched rows = $mismatch"

Summarize
