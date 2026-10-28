# =============================================================================
#  p2_delay_queue_test.ps1 —— 订单超时延迟队列（P2.1）
#
#  验证六件事：
#   1) 入队时机：下单入队、支付/取消出队、回滚的订单不入队
#   2) 到点自动触发：把 score 改成过去，等消费者把它释放掉（10 -> 91）
#   3) 重复触发幂等：同一个 bookingId 再投一次，不会重复释放库存
#   4) 兜底扫表：队列里的成员被删掉，扫表仍能把它捞出来
#   5) 队列能重建：DEL 整个 key 后 rebuild 从 DB 恢复
#   6) volatile-lru 保护：队列 key 没有 TTL（TTL = -1）
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$RT  = 2001
$Q   = 'hotel:delay:order'

$d0 = D 0; $d1 = D 1; $d2 = D 2; $d3 = D 3
$e10 = D 10; $e11 = D 11; $e12 = D 12; $e13 = D 13; $e14 = D 14; $h40 = D 40; $h41 = D 41
$f20 = D 20; $f21 = D 21; $f22 = D 22
$g30 = D 30; $g31 = D 31; $g32 = D 32

# ---------- 队列相关的小工具 ----------
function QueueScore([long]$bid) { RedisCmd @('ZSCORE', $Q, "$bid") }
function QueueDepth { RedisCmd @('ZCARD', $Q) }
function PastMs([int]$sec) { ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $sec * 1000) }

function WaitStatus([long]$bid, [string]$want, [int]$timeoutSec = 12) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $s = Sql "SELECT booking_status FROM booking WHERE booking_id=$bid"
        if ($s -eq $want) { return $s }
        Start-Sleep -Milliseconds 300
    }
    return (Sql "SELECT booking_status FROM booking WHERE booking_id=$bid")
}

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')    锚点(周一) = $($script:M0.ToString('yyyy-MM-dd'))"

"`n=== 0. 重置 ==="
ResetTestData | Out-Null
"  DB bookings=$(BCount)   队列深度=$(QueueDepth)   队列 key 的 TTL=$(RedisCmd @('TTL',$Q))"

# ---------------------------------------------------------------------------
"`n=== 1. 下单入队 + 支付出队 ==="
$c1 = NewBooking $RT $d0 $d2 1
$b1 = $c1.Body.data
$s1 = QueueScore $b1
"  下单 -> bookingId=$b1"
"    ZSCORE = $s1"
"    距今约 {0:N1} 分钟（应接近 pay-timeout-minutes=15）" -f (([double]$s1 - [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) / 60000)
Check 'booking is enqueued on create' ($s1 -ne '') ''
Check 'executeAt is ~15 minutes ahead' ([math]::Abs((([double]$s1 - [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) / 60000) - 15) -lt 1) ''

Api POST "/booking/$b1/pay" '{"payChannel":1}' | Out-Null
Start-Sleep -Milliseconds 500
$s1b = QueueScore $b1
"  支付后 -> ZSCORE = [$s1b]"
Check 'booking is dequeued after pay' ($s1b -eq '') ''

# ---------------------------------------------------------------------------
"`n=== 2. 取消出队 ==="
$c2 = NewBooking $RT $d1 $d3 1
$b2 = $c2.Body.data
"  下单 -> bookingId=$b2   ZSCORE=$(QueueScore $b2)"
Api POST "/booking/$b2/cancel" '{"reason":"test","cancelRole":1}' | Out-Null
Start-Sleep -Milliseconds 500
"  取消后 -> ZSCORE=[$(QueueScore $b2)]"
Check 'booking is dequeued after cancel' ((QueueScore $b2) -eq '') ''

# ---------------------------------------------------------------------------
"`n=== 3. 下单失败（回滚）不留队列成员 ==="
NewBooking $RT $e10 $e12 3 | Out-Null          # 占满 2 晚
$depthBefore = QueueDepth
$failResp = NewBooking $RT $e10 $e12 1              # 必然 409
"  占满后抢第 4 间 -> HTTP $($failResp.Http)   队列深度 $depthBefore -> $(QueueDepth)"
Check 'failed booking is rejected' ($failResp.Http -eq 409) ''
Check 'failed booking leaves no queue member' ((QueueDepth) -eq $depthBefore) ''

# ---------------------------------------------------------------------------
"`n=== 4. 到点自动触发（10 -> 91）==="
$c4 = NewBooking $RT $f20 $f22 1
$b4 = $c4.Body.data
"  下单 -> bookingId=$b4  status=$(Sql "SELECT booking_status FROM booking WHERE booking_id=$b4")"
"  把它的 score 改成 5 秒前（模拟已到期），等消费者..."
RedisCmd @('ZADD', $Q, "$(PastMs 5)", "$b4") | Out-Null
$st4 = WaitStatus $b4 '91' 12
"  status = $st4   cancel_role = $(Sql "SELECT cancel_role FROM booking WHERE booking_id=$b4")"
"  库存 $f20=$(DSold $RT $f20)  台账未释放行数=$(Sql "SELECT COUNT(*) FROM booking_inventory_link WHERE booking_id=$b4 AND released=0")"
Check 'auto-expired by the delay queue (10 -> 91)' ($st4 -eq '91') "actual=$st4"
Check 'cancel_role = 3 SYSTEM' ((Sql "SELECT cancel_role FROM booking WHERE booking_id=$b4") -eq '3') ''
Check 'inventory released' ((DSold $RT $f20) -eq '0') ''
Check 'ledger fully released' ((Sql "SELECT COUNT(*) FROM booking_inventory_link WHERE booking_id=$b4 AND released=0") -eq '0') ''
Check 'queue member consumed' ((QueueScore $b4) -eq '') ''

# ---------------------------------------------------------------------------
"`n=== 5. 重复触发幂等（再投一次同一个 bookingId）==="
$releasedBefore = Sql "SELECT COUNT(*) FROM booking_inventory_link WHERE booking_id=$b4 AND released=1"
$soldBefore = DSold $RT $f20
RedisCmd @('ZADD', $Q, "$(PastMs 3)", "$b4") | Out-Null
Start-Sleep -Seconds 3
"  再投一次后：status=$(Sql "SELECT booking_status FROM booking WHERE booking_id=$b4")  库存 $f20=$soldBefore -> $(DSold $RT $f20)  已释放行数 $releasedBefore -> $(Sql "SELECT COUNT(*) FROM booking_inventory_link WHERE booking_id=$b4 AND released=1")"
Check 'status stays 91 (no double transition)' ((Sql "SELECT booking_status FROM booking WHERE booking_id=$b4") -eq '91') ''
Check 'inventory NOT double-released' ((DSold $RT $f20) -eq $soldBefore) ''
Check 'ledger release count unchanged' ((Sql "SELECT COUNT(*) FROM booking_inventory_link WHERE booking_id=$b4 AND released=1") -eq $releasedBefore) ''

# ---------------------------------------------------------------------------
"`n=== 6. 兜底扫表（队列成员被删掉也能捞回来）==="
$c6 = NewBooking $RT $g30 $g32 1
$b6 = $c6.Body.data
"  下单 -> bookingId=$b6"
RedisCmd @('ZREM', $Q, "$b6") | Out-Null
Sql "UPDATE booking SET create_time = NOW() - INTERVAL 30 MINUTE WHERE booking_id=$b6" | Out-Null
"  删掉它的队列成员，并把 create_time 往前拨 30 分钟（模拟队列丢任务）"
"  队列里还有它吗: [$(QueueScore $b6)]"
$sw = Api POST '/booking/delay/sweep' ''
"  POST /booking/delay/sweep -> HTTP $($sw.Http)  expired=$($sw.Body.data.expired)"
$st6 = Sql "SELECT booking_status FROM booking WHERE booking_id=$b6"
"  status = $st6   库存 $g30=$(DSold $RT $g30)"
Check 'sweep expired it anyway (10 -> 91)' ($st6 -eq '91') "actual=$st6"
Check 'sweep released inventory' ((DSold $RT $g30) -eq '0') ''

"`n  -- 老的 /booking/expire-scan 仍然可用（同一个实现）--"
$c6b = NewBooking $RT $e13 $e14 1
$es = Api POST '/booking/expire-scan' ''
"  POST /booking/expire-scan -> HTTP $($es.Http)  返回=$($es.Body.data)"
Check '/booking/expire-scan still works' ($es.Http -eq 200) ''

# ---------------------------------------------------------------------------
"`n=== 7. 队列能重建 ==="
$c7a = NewBooking $RT $g31 $g32 1
$c7b = NewBooking $RT $h40 $h41 1
NewBooking $RT $f21 $f22 1 | Out-Null
"  新建了 3 个待支付订单，当前队列深度=$(QueueDepth)"
RedisCmd @('DEL', $Q) | Out-Null
"  DEL 整个队列 key -> 深度=$(QueueDepth)  （模拟 Redis 被清空）"
$rb = Api POST '/booking/delay/rebuild' ''
"  POST /booking/delay/rebuild -> HTTP $($rb.Http)  requeued=$($rb.Body.data.requeued)  depth=$($rb.Body.data.stat.depth)  due=$($rb.Body.data.stat.due)"
Check 'rebuild restored every pending booking' ($rb.Body.data.requeued -ge 3) "requeued=$($rb.Body.data.requeued)"

# ---------------------------------------------------------------------------
"`n=== 8. volatile-lru 保护：队列 key 没有 TTL ==="
$ttl = RedisCmd @('TTL', $Q)
"  TTL $Q = $ttl   （-1 = 永不过期，所以 volatile-lru 下不会被淘汰）"
Check 'delay queue key has no TTL (-1)' ($ttl -eq '-1') "actual=$ttl"

"`n  -- 对比：库存缓存 key 是有 TTL 的，所以仍可被淘汰 --"
$invKey = (RedisCmd @('KEYS','hotel:inv:*')) -split "`n" | Where-Object { $_.Trim() } | Select-Object -First 1
if ($invKey) { "  TTL $invKey = $(RedisCmd @('TTL', $invKey.Trim()))" } else { "  （当前没有库存缓存键）" }
Check 'inventory cache keys do have a TTL' ($invKey -eq $null -or (RedisCmd @('TTL', $invKey.Trim())) -gt 0) ''

# ---------------------------------------------------------------------------
"`n=== 9. 统计接口 ==="
$st = Api GET '/booking/delay/stat' ''
"  GET /booking/delay/stat -> depth=$($st.Body.data.depth)  due=$($st.Body.data.due)  oldestAgeSeconds=$($st.Body.data.oldestAgeSeconds)"
Check 'stat returns depth' ($null -ne $st.Body.data.depth) ''
Check 'stat returns due'   ($null -ne $st.Body.data.due) ''

# ---------------------------------------------------------------------------
"`n=== 10. 最终一致性 ==="
$mismatch = Sql @"
SELECT COUNT(*) FROM room_inventory i
LEFT JOIN (SELECT room_type_id, stay_date, SUM(room_count) n FROM booking_inventory_link WHERE released=0 GROUP BY room_type_id, stay_date) l
  ON l.room_type_id=i.room_type_id AND l.stay_date=i.stay_date
WHERE i.sold_rooms <> IFNULL(l.n,0)
"@
Check 'no drift between ledger and inventory' ($mismatch -eq '0') "mismatched rows = $mismatch"
"  Redis 与 DB 的库存差异 = $(DiffCount $RT $d0 (D 35))"
Check 'Redis inventory in sync' ((DiffCount $RT $d0 (D 35)) -eq 0) ''

Summarize
