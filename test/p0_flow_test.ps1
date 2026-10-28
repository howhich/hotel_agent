# =============================================================================
#  p0_flow_test.ps1 —— P0 主链路（下单 / 支付 / 取消 / 超卖 / 超时失效）
#
#  日期全部相对今天计算（见 _testlib.ps1），不会再因为"过几天再跑"而失效。
#  $d0/$d3 这类日期都锚定到"下一个周一"，所以金额断言是固定值（400/晚）。
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$RT  = 2001         # 高级大床房，总 3 间，免费取消
$RT2 = 2002         # 豪华双床房，总 2 间，限时免费（阶梯退款）

# 锚定"下一个周一" + n 天
$d0  = D 0          # 周一（入住）
$d3  = D 3          # 周四（离店）-> 3 晚，全工作日 400/晚
$o8  = D 8          # 周二（超卖测试入住）
$o10 = D 10         # 周四（离店）-> 2 晚
$t14 = D 14
$t16 = D 16

# 阶梯退款按【小时】分档，所以用"今天 + n"：
#   checkIn = 今天+2 的 00:00 -> 距今 24~48 小时 -> 命中 >=24h 档 -> 退 50%
$h2 = TD 2
$h4 = TD 4

"=== 环境 ==="
"  今天           = $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
"  测试锚点(周一)  = $($script:M0.ToString('yyyy-MM-dd'))"
"  主链路窗口      = $d0 ~ $d3  (3 晚)"

"`n=== 0. 重置测试数据 ==="
$nk = ResetTestData
"  DB bookings=$(BCount)   Redis 清掉 $nk 个库存键"

# ---------------------------------------------------------------------------
"`n=== 1. 可订查询（全新日期，应可订）==="
$a = Api POST '/booking/availability' (JsonBody @{ roomTypeId=$RT; checkIn=$d0; checkOut=$d3; roomCount=1 })
"  HTTP $($a.Http)  available=$($a.Body.data.available)  nights=$($a.Body.data.nights)  minRemain=$($a.Body.data.minRemain)"
foreach ($x in $a.Body.data.details) { "    $($x.stayDate)  total=$($x.totalRooms) sold=$($x.soldRooms) remain=$($x.remain)" }
Check 'available=true on virgin dates' ($a.Body.data.available -eq $true) '(this used to be a bug)'
Check 'nights = 3 (half-open [in,out))' ($a.Body.data.nights -eq 3) ''

# ---------------------------------------------------------------------------
"`n=== 2. 报价（逐晚累加）==="
$q = Api POST '/booking/quote' (JsonBody @{ roomTypeId=$RT; checkIn=$d0; checkOut=$d3; roomCount=1 })
"  HTTP $($q.Http)  perRoom=$($q.Body.data.pricePerRoom)  total=$($q.Body.data.totalAmount)"
foreach ($x in $q.Body.data.nightPrices) { "    $($x.stayDate)  price=$($x.price)  weekend=$($x.weekend)" }
Check 'perRoom = 1200 (3 weekdays x 400)' ([decimal]$q.Body.data.pricePerRoom -eq 1200) ''

# ---------------------------------------------------------------------------
"`n=== 3. 下单 ==="
$c = NewBooking $RT $d0 $d3 1
$bid = $c.Body.data
"  HTTP $($c.Http)  bookingId = $bid"
Check 'create returns bookingId' ($null -ne $bid -and $bid -gt 0) ''

"`n  -- booking --"
& $script:Mysql @script:Margs -e "SELECT booking_status, nights, room_count, total_amount, LEFT(cancel_policy_snapshot,40) AS snapshot FROM booking WHERE booking_id=$bid" 2>$null
"`n  -- ledger（应 3 行，不含离店日）--"
& $script:Mysql @script:Margs -e "SELECT stay_date, room_count, released FROM booking_inventory_link WHERE booking_id=$bid ORDER BY stay_date" 2>$null
"`n  -- inventory --"
& $script:Mysql @script:Margs -e "SELECT stay_date, total_rooms, sold_rooms, version FROM room_inventory WHERE room_type_id=$RT AND stay_date BETWEEN '$d0' AND '$d3' ORDER BY stay_date" 2>$null

Check 'ledger has 3 rows (excludes checkout day)' ((Sql "SELECT COUNT(*) FROM booking_inventory_link WHERE booking_id=$bid") -eq '3') ''
Check 'inventory sold=1 on the 3 stayed days' ((Sql "SELECT SUM(sold_rooms) FROM room_inventory WHERE room_type_id=$RT AND stay_date >= '$d0' AND stay_date < '$d3'") -eq '3') ''

# ---------------------------------------------------------------------------
"`n=== 4. 支付 10 -> 20 ==="
$p = Api POST "/booking/$bid/pay" '{"payChannel":1,"tradeNo":"TEST-001"}'
"  HTTP $($p.Http)"
$st = Sql "SELECT booking_status FROM booking WHERE booking_id=$bid"
Check 'status = 20 CONFIRMED' ($st -eq '20') "actual=$st"

$p2 = Api POST "/booking/$bid/pay" '{"payChannel":1}'
"  pay again -> HTTP $($p2.Http)  msg=$($p2.Body.message)"
Check 'double pay rejected with 409' ($p2.Http -eq 409) ''

# ---------------------------------------------------------------------------
"`n=== 5. 取消（免费取消 -> 全额退）==="
$cx = Api POST "/booking/$bid/cancel" '{"reason":"trip changed","cancelRole":1}'
"  HTTP $($cx.Http)  paid=$($cx.Body.data.paidAmount)  refund=$($cx.Body.data.refundAmount)  ratio=$($cx.Body.data.refundRatioText)  policy=$($cx.Body.data.policyName)"
Check 'status = 90 CANCELLED' ((Sql "SELECT booking_status FROM booking WHERE booking_id=$bid") -eq '90') ''
Check 'refund = 1200 (full)' ([decimal]$cx.Body.data.refundAmount -eq 1200) ''
Check 'inventory released' ((Sql "SELECT SUM(sold_rooms) FROM room_inventory WHERE room_type_id=$RT AND stay_date >= '$d0' AND stay_date < '$d3'") -eq '0') ''
Check 'ledger all released=1' ((Sql "SELECT COUNT(*) FROM booking_inventory_link WHERE booking_id=$bid AND released=1") -eq '3') ''

$cx2 = Api POST "/booking/$bid/cancel" '{"reason":"again"}'
"  cancel again -> HTTP $($cx2.Http)  msg=$($cx2.Body.message)"
Check 'double cancel rejected with 409' ($cx2.Http -eq 409) ''

# ---------------------------------------------------------------------------
"`n=== 6. 超卖防护（RT2001 只有 3 间）==="
$big = NewBooking $RT $o8 $o10 3
"  book 3 rooms -> HTTP $($big.Http)  bookingId=$($big.Body.data)"
Check 'booking all 3 rooms succeeds' ($big.Http -eq 200) ''

$over = NewBooking $RT $o8 $o10 1
"  take 1 more -> HTTP $($over.Http)  msg=$($over.Body.message)"
Check 'oversell rejected with 409' ($over.Http -eq 409) ''

"`n  -- 失败后不能留下半占（事务应整体回滚）--"
& $script:Mysql @script:Margs -e "SELECT stay_date, total_rooms, sold_rooms FROM room_inventory WHERE room_type_id=$RT AND stay_date >= '$o8' AND stay_date < '$o10' ORDER BY stay_date" 2>$null
Check 'no partial occupancy (still 2 days x 3 rooms = 6)' ((Sql "SELECT SUM(sold_rooms) FROM room_inventory WHERE room_type_id=$RT AND stay_date >= '$o8' AND stay_date < '$o10'") -eq '6') ''

# ---------------------------------------------------------------------------
"`n=== 7. 阶梯退款（RT2002 限时免费，距入住 24~48 小时 -> 50%）==="
"  checkIn=$h2（今天+2）"
$cb = NewBooking $RT2 $h2 $h4 1
$bid2 = $cb.Body.data
"  book RT2002 -> HTTP $($cb.Http)  bookingId=$bid2"
Api POST "/booking/$bid2/pay" '{"payChannel":2}' | Out-Null
$cc = Api POST "/booking/$bid2/cancel" '{"reason":"plan b","cancelRole":1}'
"  cancel -> HTTP $($cc.Http)  paid=$($cc.Body.data.paidAmount)  refund=$($cc.Body.data.refundAmount)  ratio=$($cc.Body.data.refundRatioText)  policy=$($cc.Body.data.policyName)"
Check 'tiered refund = 50% of paid' ([decimal]$cc.Body.data.refundAmount -eq [decimal]$cc.Body.data.paidAmount * 0.5) ''

# ---------------------------------------------------------------------------
"`n=== 8. 超时失效 10 -> 91 ==="
$ct = NewBooking $RT $t14 $t16 1
$bid3 = $ct.Body.data
"  create -> HTTP $($ct.Http)  bookingId=$bid3  (保持 10 待支付)"
Sql "UPDATE booking SET create_time = NOW() - INTERVAL 20 MINUTE WHERE booking_id=$bid3" | Out-Null
"  把 create_time 往前拨 20 分钟（模拟 15 分钟超时）"
$scan = Api POST '/booking/expire-scan' ''
"  expire-scan -> HTTP $($scan.Http)  expired=$($scan.Body.data)"
$st3 = Sql "SELECT booking_status FROM booking WHERE booking_id=$bid3"
$cr3 = Sql "SELECT cancel_role FROM booking WHERE booking_id=$bid3"
"  status=$st3 (91=EXPIRED)  cancel_role=$cr3 (3=SYSTEM)"
Check 'status = 91 EXPIRED' ($st3 -eq '91') "actual=$st3"
Check 'cancel_role = 3 SYSTEM' ($cr3 -eq '3') "actual=$cr3"
Check 'inventory released on expiry' ((Sql "SELECT SUM(sold_rooms) FROM room_inventory WHERE room_type_id=$RT AND stay_date >= '$t14' AND stay_date < '$t16'") -eq '0') ''

"`n  -- 90 与 91 确实分开统计 --"
& $script:Mysql @script:Margs -e "SELECT booking_status, cancel_role, COUNT(*) AS cnt FROM booking GROUP BY booking_status, cancel_role ORDER BY booking_status" 2>$null

# ---------------------------------------------------------------------------
"`n=== 9. 售罄后查询反映真实状态 ==="
$a2 = Api POST '/booking/availability' (JsonBody @{ roomTypeId=$RT; checkIn=$o8; checkOut=$o10; roomCount=1 })
"  HTTP $($a2.Http)  available=$($a2.Body.data.available)  minRemain=$($a2.Body.data.minRemain)"
Check 'sold-out dates report available=false' ($a2.Body.data.available -eq $false) ''

Summarize
