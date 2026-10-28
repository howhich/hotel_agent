# =============================================================================
#  p1_idempotency_test.ps1 —— P1.4 接口幂等
#
#  验证四件事：
#   1) 同一请求连点两次，只产生一张单，第二次回放首次的 booking_id
#   2) 参数变了就是新请求（不会被误判成重复）
#   3) 客户端给的 Idempotency-Key 优先（重试最可靠）
#   4) Redis 里的幂等键被清掉后，仍能靠 DB 唯一键兜底回放（第二层防御）
#   5) 业务失败不占用幂等键 —— 用户可以重试，而不是被当成"重复提交"
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$RT = 2001
$d0 = D 0; $d1 = D 1; $d2 = D 2; $d3 = D 3; $d4 = D 4
$e20 = D 20; $e21 = D 21
$f40 = D 40; $f41 = D 41

function ClearIdemKeys {
    $k = RedisCmd @('KEYS','hotel:idem:*')
    $n = 0
    if ($k) { foreach ($x in ($k -split "`n")) { if ($x.Trim()) { RedisCmd @('DEL', $x.Trim()) | Out-Null; $n++ } } }
    return $n
}

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')    锚点(周一) = $($script:M0.ToString('yyyy-MM-dd'))"

"`n=== 0. 重置 ==="
ResetTestData | Out-Null
Sql "DELETE FROM idempotent_record" | Out-Null
$nk = ClearIdemKeys
"  DB bookings=$(BCount)   Redis 幂等键清掉 $nk 个   idempotent_record=$(Sql 'SELECT COUNT(*) FROM idempotent_record')"

# ---------------------------------------------------------------------------
"`n=== 1. 同一请求连点两次 -> 只下一张单 ==="
$body = JsonBody @{ userId=9001; hotelId=1001; roomTypeId=$RT; checkIn=$d0; checkOut=$d3; roomCount=1; guestCount=1 }
$r1 = Api POST '/booking' $body
"  1st -> HTTP $($r1.Http)  bookingId=$($r1.Body.data)"
$r2 = Api POST '/booking' $body
"  2nd -> HTTP $($r2.Http)  bookingId=$($r2.Body.data)  replay=$($r2.Headers['X-Idempotent-Replay'])"
Check 'both calls return 200'          ($r1.Http -eq 200 -and $r2.Http -eq 200) ''
Check 'same bookingId returned'        ("$($r1.Body.data)" -eq "$($r2.Body.data)") ''
Check '2nd call marked as replay'      ($r2.Headers['X-Idempotent-Replay'] -eq 'true') ''
Check 'only ONE booking row'           ((BCount) -eq '1') "bookings=$(BCount)"
Check 'only ONE room sold per day'     ((DSold $RT $d0) -eq '1') "sold=$(DSold $RT $d0)"
Check 'ledger has 3 rows (not 6)'      ((Sql "SELECT COUNT(*) FROM booking_inventory_link") -eq '3') ''

"`n  -- idempotent_record --"
& $script:Mysql @script:Margs -e "SELECT biz_type, status, result_ref FROM idempotent_record" 2>$null

# ---------------------------------------------------------------------------
"`n=== 2. 参数变了就是新请求 ==="
$body2 = JsonBody @{ userId=9001; hotelId=1001; roomTypeId=$RT; checkIn=$d0; checkOut=$d4; roomCount=1; guestCount=1 }
$r3 = Api POST '/booking' $body2
"  改离店日 -> HTTP $($r3.Http)  bookingId=$($r3.Body.data)  replay=$($r3.Headers['X-Idempotent-Replay'])"
Check 'different params -> new booking' ((BCount) -eq '2') "bookings=$(BCount)"
Check 'not marked as replay'            ($r3.Headers['X-Idempotent-Replay'] -ne 'true') ''

# ---------------------------------------------------------------------------
"`n=== 3. 客户端 Idempotency-Key 优先（哪怕参数不同）==="
$hdr = @{ 'Idempotency-Key' = 'client-key-DEMO-001' }
$h1 = Api POST '/booking' (JsonBody @{ userId=9001; hotelId=1001; roomTypeId=$RT; checkIn=$f40; checkOut=$f41; roomCount=1; guestCount=1 }) -Headers $hdr
"  1st (key=client-key-DEMO-001, $f40~$f41) -> HTTP $($h1.Http)  bookingId=$($h1.Body.data)"
$before = BCount
$h2 = Api POST '/booking' (JsonBody @{ userId=9001; hotelId=1001; roomTypeId=$RT; checkIn=$d0; checkOut=$d3; roomCount=1; guestCount=1 }) -Headers $hdr
"  2nd (same key, but different dates)       -> HTTP $($h2.Http)  bookingId=$($h2.Body.data)  replay=$($h2.Headers['X-Idempotent-Replay'])"
Check 'same client key -> replays first result' ("$($h1.Body.data)" -eq "$($h2.Body.data)") ''
Check 'same client key -> replay header set'    ($h2.Headers['X-Idempotent-Replay'] -eq 'true') ''
Check 'no extra booking created'                ((BCount) -eq $before) "bookings=$before"

# ---------------------------------------------------------------------------
"`n=== 4. Redis 幂等键被清掉后，DB 唯一键兜底 ==="
"  （模拟 allkeys-lru 淘汰 / Redis 重启）"
$nk2 = ClearIdemKeys
"  Redis 幂等键清掉 $nk2 个，DB 记录还在 $(Sql 'SELECT COUNT(*) FROM idempotent_record') 条"
$r5 = Api POST '/booking' $body
"  重放同一请求 -> HTTP $($r5.Http)  bookingId=$($r5.Body.data)  replay=$($r5.Headers['X-Idempotent-Replay'])"
Check 'replay still works from DB alone'  ("$($r5.Body.data)" -eq "$($r1.Body.data)") ''
Check 'still marked as replay'            ($r5.Headers['X-Idempotent-Replay'] -eq 'true') ''
Check 'still no duplicate booking'        ((BCount) -eq ($before)) "bookings=$(BCount)"

# ---------------------------------------------------------------------------
"`n=== 5. 业务失败不占用幂等键（用户能重试）==="
"  先用 3 间占满 $e20 / $e21，再拿同样的 1 间请求连发两次"
NewBooking $RT $e20 $e21 3 | Out-Null
"    blocker 后: $e20=$(DSold $RT $e20)  $e21=$(DSold $RT $e21)"
$failBody = JsonBody @{ userId=9001; hotelId=1001; roomTypeId=$RT; checkIn=$e20; checkOut=$e21; roomCount=1; guestCount=1 }
$f1 = Api POST '/booking' $failBody
"  1st attempt -> HTTP $($f1.Http)  msg=$($f1.Body.message)"
$f2 = Api POST '/booking' $failBody
"  2nd attempt -> HTTP $($f2.Http)  msg=$($f2.Body.message)"
Check 'attempt fails with 409'                   ($f1.Http -eq 409) ''
Check 'retry also fails with 409'                ($f2.Http -eq 409) ''
Check 'retry says stock shortage, NOT duplicate' ($f2.Body.message -like '*房量不足*') "msg=$($f2.Body.message)"
Check 'no RUNNING record left behind' ((Sql "SELECT COUNT(*) FROM idempotent_record WHERE status=0") -eq '0') ''

# 修好条件后，同一请求应该能成功（证明失败没有把它锁死）
$retryOk = Api POST '/booking' (JsonBody @{ userId=9001; hotelId=1001; roomTypeId=2002; checkIn=$e20; checkOut=$e21; roomCount=1; guestCount=1 })
"  换成 RT2002（有空房）-> HTTP $($retryOk.Http)  bookingId=$($retryOk.Body.data)"
Check 'a fresh request still succeeds' ($retryOk.Http -eq 200) ''

# ---------------------------------------------------------------------------
"`n=== 6. 最终一致性 ==="
"  bookings = $(BCount)   idempotent_record = $(Sql 'SELECT COUNT(*) FROM idempotent_record')"
& $script:Mysql @script:Margs -e "SELECT biz_type, status, result_ref, LEFT(idem_key,50) AS idem_key FROM idempotent_record ORDER BY id" 2>$null
$mismatch = Sql @"
SELECT COUNT(*) FROM room_inventory i
LEFT JOIN (SELECT room_type_id, stay_date, SUM(room_count) n FROM booking_inventory_link WHERE released=0 GROUP BY room_type_id, stay_date) l
  ON l.room_type_id=i.room_type_id AND l.stay_date=i.stay_date
WHERE i.sold_rooms <> IFNULL(l.n,0)
"@
Check 'no drift between ledger and inventory' ($mismatch -eq '0') "mismatched rows = $mismatch"
"  Redis 与 DB 的库存差异 = $(DiffCount $RT $d0 (D 45))"
Check 'Redis inventory in sync' ((DiffCount $RT $d0 (D 45)) -eq 0) ''

Summarize
