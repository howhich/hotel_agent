# =============================================================================
#  p0_1_reschedule_test.ps1 —— 改期（只动台账差集）
#
#  日期锚定"下一个周一"，所以金额断言是固定值（工作日 400）。
#  最强的一条证据：未受影响日期的 inventory.version 改期前后【完全相同】。
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$RT = 2001

$d0  = D 0          # 周一
$d3  = D 3          # 周四（3 晚）
$d5  = D 5          # 周六（延长到 5 晚）
$d4  = D 4

$b8  = D 8          # 周二
$b9  = D 9
$b10 = D 10
$b11 = D 11         # 周五
$b12 = D 12         # 周六

$c21 = D 21         # 周一
$c22 = D 22
$c23 = D 23
$c24 = D 24

$e28 = D 28; $e31 = D 31
$e35 = D 35; $e37 = D 37

$f42 = D 42; $f43 = D 43; $f44 = D 44; $f45 = D 45
$f48 = D 48; $f49 = D 49; $f50 = D 50; $f51 = D 51

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')    锚点(周一) = $($script:M0.ToString('yyyy-MM-dd'))"

"`n=== 0. 重置 ==="
$nk = ResetTestData
"  DB bookings=$(BCount)  Redis 清掉 $nk 键"

# ---------------------------------------------------------------------------
"`n=== A. 延长：$d0 ~ $d3  ->  $d0 ~ $d5 ==="
$bA = (NewPaidBooking $RT $d0 $d3).Body.data
"  B_A = $bA  (已支付 1200)"
$v0 = Sql "SELECT version FROM room_inventory WHERE room_type_id=$RT AND stay_date='$d0'"
$rA = Api POST "/booking/$bA/reschedule" (JsonBody @{ newCheckIn=$d0; newCheckOut=$d5 })
"  HTTP $($rA.Http)"
"    released  = [$($rA.Body.data.releasedDates -join ', ')]"
"    occupied  = [$($rA.Body.data.occupiedDates -join ', ')]"
"    untouched = [$($rA.Body.data.untouchedDates -join ', ')]"
"    $($rA.Body.data.oldTotalAmount) -> $($rA.Body.data.newTotalAmount)   差价 $($rA.Body.data.priceDiff)"
Check 'extend: nothing released'   ($rA.Body.data.releasedDates.Count -eq 0) ''
Check 'extend: occupies 2 days'    (($rA.Body.data.occupiedDates -join ',') -eq "$d3,$d4") ''
Check 'extend: 3 days untouched'   (($rA.Body.data.untouchedDates -join ',') -eq "$d0,$(D 1),$(D 2)") ''
Check 'extend: total 1200 -> 2000' ([decimal]$rA.Body.data.newTotalAmount -eq 2000) ''
Check 'extend: diff = +800'        ([decimal]$rA.Body.data.priceDiff -eq 800) ''
Check 'extend: untouched day version UNCHANGED' ((Sql "SELECT version FROM room_inventory WHERE room_type_id=$RT AND stay_date='$d0'") -eq $v0) "version=$v0"

# ---------------------------------------------------------------------------
"`n=== B. 平移重叠（关键案例）：$b8 ~ $b11  ->  $b9 ~ $b12 ==="
"  3 晚整体后移 1 天：只有 $b8 走、只有 $b12 进，$b9 和 $b10 一下不动"
$bB = (NewPaidBooking $RT $b8 $b11).Body.data
"  B_B = $bB"
$v9  = Sql "SELECT version FROM room_inventory WHERE room_type_id=$RT AND stay_date='$b9'"
$v10 = Sql "SELECT version FROM room_inventory WHERE room_type_id=$RT AND stay_date='$b10'"
$rB = Api POST "/booking/$bB/reschedule" (JsonBody @{ newCheckIn=$b9; newCheckOut=$b12 })
"  HTTP $($rB.Http)"
"    released  = [$($rB.Body.data.releasedDates -join ', ')]"
"    occupied  = [$($rB.Body.data.occupiedDates -join ', ')]"
"    untouched = [$($rB.Body.data.untouchedDates -join ', ')]   差价 $($rB.Body.data.priceDiff)"
Check 'shift: releases ONLY the head day' (($rB.Body.data.releasedDates -join ',') -eq $b8) ''
# 被"进入"的那一天正好是【原离店日】$b11，不是新离店日 $b12
Check 'shift: occupies ONLY the old checkout day' (($rB.Body.data.occupiedDates -join ',') -eq $b11) ''
Check 'shift: middle 2 days untouched'    (($rB.Body.data.untouchedDates -join ',') -eq "$b9,$b10") ''
Check 'shift: untouched version UNCHANGED (a)' ((Sql "SELECT version FROM room_inventory WHERE room_type_id=$RT AND stay_date='$b9'") -eq $v9) "version=$v9"
Check 'shift: untouched version UNCHANGED (b)' ((Sql "SELECT version FROM room_inventory WHERE room_type_id=$RT AND stay_date='$b10'") -eq $v10) "version=$v10"
Check 'shift: head day released (sold 0)' ((DSold $RT $b8) -eq '0') ''
Check 'shift: entered day held (sold 1)'  ((DSold $RT $b11) -eq '1') ''

# ---------------------------------------------------------------------------
"`n=== C. 缩短：$c21 ~ $c24  ->  $c21 ~ $c22 ==="
$bC = (NewPaidBooking $RT $c21 $c24).Body.data
$rC = Api POST "/booking/$bC/reschedule" (JsonBody @{ newCheckIn=$c21; newCheckOut=$c22 })
"  HTTP $($rC.Http)  released=[$($rC.Body.data.releasedDates -join ',')]  occupied=[$($rC.Body.data.occupiedDates -join ',')]  untouched=[$($rC.Body.data.untouchedDates -join ',')]  差价=$($rC.Body.data.priceDiff)"
Check 'shrink: releases the dropped days' (($rC.Body.data.releasedDates -join ',') -eq "$c22,$c23") ''
Check 'shrink: occupies nothing'          ($rC.Body.data.occupiedDates.Count -eq 0) ''
Check 'shrink: diff = -800'               ([decimal]$rC.Body.data.priceDiff -eq -800) ''
Check 'shrink: kept day still held'       ((DSold $RT $c21) -eq '1') ''

# ---------------------------------------------------------------------------
"`n=== D. 完全错开 + 再改回（测台账 upsert）==="
$bD = (NewPaidBooking $RT $e28 $e31).Body.data
$rD1 = Api POST "/booking/$bD/reschedule" (JsonBody @{ newCheckIn=$e35; newCheckOut=$e37 })
"  错开 -> released=[$($rD1.Body.data.releasedDates -join ',')]  occupied=[$($rD1.Body.data.occupiedDates -join ',')]"
Check 'disjoint: released all 3 old days' ($rD1.Body.data.releasedDates.Count -eq 3) ''
Check 'disjoint: occupied 2 new days'     ($rD1.Body.data.occupiedDates.Count -eq 2) ''

"  再改回：旧台账行还在（released=1），upsert 应把它翻回 0，而不是撞唯一键"
$rD2 = Api POST "/booking/$bD/reschedule" (JsonBody @{ newCheckIn=$e28; newCheckOut=$e31 })
"  HTTP $($rD2.Http)  released=[$($rD2.Body.data.releasedDates -join ',')]  occupied=[$($rD2.Body.data.occupiedDates -join ',')]  差价=$($rD2.Body.data.priceDiff)"
Check 'change-back succeeds (upsert, no unique-key clash)' ($rD2.Http -eq 200) ''
Check 'change-back: re-occupies 3 days'   ($rD2.Body.data.occupiedDates.Count -eq 3) ''
Check 'change-back: no duplicate (booking,date)' ((Sql "SELECT COUNT(*) FROM booking_inventory_link WHERE booking_id=$bD") -eq (Sql "SELECT COUNT(DISTINCT stay_date) FROM booking_inventory_link WHERE booking_id=$bD")) ''
Check 'change-back: all 3 held again'     ((Sql "SELECT COUNT(*) FROM booking_inventory_link WHERE booking_id=$bD AND released=0") -eq '3') ''

# ---------------------------------------------------------------------------
"`n=== E. 失败必须整体回滚 ==="
"  先用 3 间占满 $f49 和 $f50"
$blk = NewBooking $RT $f49 $f51 3
"    blocker HTTP=$($blk.Http)  $f49=$(DSold $RT $f49)  $f50=$(DSold $RT $f50)"
$bE = (NewPaidBooking $RT $f42 $f45).Body.data
"  B_E = $bE ($f42 ~ $f45)，尝试改到 $f48 ~ $f51（$f49 已满）"
$before48 = DSold $RT $f48
$rE = Api POST "/booking/$bE/reschedule" (JsonBody @{ newCheckIn=$f48; newCheckOut=$f51 })
"  HTTP $($rE.Http)  msg=$($rE.Body.message)"
Check 'reschedule fails with 409' ($rE.Http -eq 409) ''
Check 'rollback: old days still held (release undone)' ((DSold $RT $f42) -eq '1' -and (DSold $RT $f43) -eq '1' -and (DSold $RT $f44) -eq '1') ''
Check 'rollback: new day NOT held (occupy undone)' ((DSold $RT $f48) -eq $before48) "was $before48"
Check 'rollback: booking dates unchanged' ((Sql "SELECT CONCAT(check_in,'~',check_out) FROM booking WHERE booking_id=$bE") -eq "$f42~$f45") ''
Check 'rollback: ledger still 3 held' ((Sql "SELECT COUNT(*) FROM booking_inventory_link WHERE booking_id=$bE AND released=0") -eq '3') ''

# ---------------------------------------------------------------------------
"`n=== F. 状态校验 + 入住当天取消全额退 ==="
"  新开一单 checkIn=今天（$(TD 0)），验证 hoursUntilCheckIn 为负时仍全退"
$bF = (NewPaidBooking $RT (TD 0) (TD 1)).Body.data
$cxF = Api POST "/booking/$bF/cancel" '{"reason":"same-day","cancelRole":1}'
"  cancel -> HTTP $($cxF.Http)  paid=$($cxF.Body.data.paidAmount)  refund=$($cxF.Body.data.refundAmount)  ratio=$($cxF.Body.data.refundRatioText)"
Check 'same-day cancel refunds 100% (not 0)' ([decimal]$cxF.Body.data.refundAmount -eq [decimal]$cxF.Body.data.paidAmount) ''

Api POST "/booking/$bA/cancel" '{"reason":"cleanup","cancelRole":1}' | Out-Null
$rF = Api POST "/booking/$bA/reschedule" (JsonBody @{ newCheckIn=(D 40); newCheckOut=(D 41) })
"  改已取消的订单 -> HTTP $($rF.Http)  msg=$($rF.Body.message)"
Check 'cancelled booking cannot be rescheduled (409)' ($rF.Http -eq 409) ''

$rG = Api POST "/booking/$bB/reschedule" (JsonBody @{ newCheckIn=$b9; newCheckOut=$b12 })
"  改成相同日期 -> HTTP $($rG.Http)  msg=$($rG.Body.message)"
Check 'same dates rejected (400)' ($rG.Http -eq 400) ''

# ---------------------------------------------------------------------------
"`n=== G. 台账与库存零漂移（全表）==="
$mismatch = Sql @"
SELECT COUNT(*) FROM room_inventory i
LEFT JOIN (SELECT room_type_id, stay_date, SUM(room_count) n FROM booking_inventory_link WHERE released=0 GROUP BY room_type_id, stay_date) l
  ON l.room_type_id=i.room_type_id AND l.stay_date=i.stay_date
WHERE i.sold_rooms <> IFNULL(l.n,0)
"@
Check 'no drift between ledger and inventory' ($mismatch -eq '0') "mismatched rows = $mismatch"

Summarize
