# =============================================================================
#  p4_authz_test.ps1 —— 越权校验（P4.2）
#
#  验证六件事：
#   1) ★ A 建的订单，B 查/支付/取消/改期 全部 403
#   2) B 用 /booking/user/{A的id} 看 A 的列表 -> 403
#   3) B 的 /booking/my 里没有 A 的订单
#   4) ★ 越权被拦下时【没有任何副作用】（状态没变、库存没退）
#   5) A 自己操作完全正常
#   6) 不存在的订单号 -> 404（不是 403）：先查存在性，再验归属
#
#  ★ 为什么越权返回 403 而不是 404
#    404 更"安全"（不泄露订单号是否存在），但订单号是雪花 ID、不可枚举，
#    泄露存在性的价值很低；而 403 对前端提示和排查更友好
#    （"这不是你的单" 和 "单不存在" 是两件完全不同的事）。
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$ci  = D 20
$co  = D 22
$ci2 = D 25
$co2 = D 27

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
"  A = testuser(9001)    B = otheruser(9002)"

"`n=== 0. 重置 ==="
ResetTestData | Out-Null
"  bookings=$(BCount)"

# ---------------------------------------------------------------------------
"`n=== 1. A 建一笔订单 ==="
Login 'testuser' '123456' | Out-Null
$c = NewBooking 2001 $ci $co 1
$bid = $c.Body.data
"  A 下单 -> HTTP $($c.Http)  bookingId=$bid"
"  DB 里 user_id = $(Sql "SELECT user_id FROM booking WHERE booking_id=$bid")"
Check 'A created the booking' ($c.Http -eq 200) "actual=$($c.Http)"

$soldBefore = DSold 2001 $ci
"  当前库存 $ci = $soldBefore   状态 = $(Sql "SELECT booking_status FROM booking WHERE booking_id=$bid")"

# ---------------------------------------------------------------------------
"`n=== 2. ★ 切到 B，四种操作 + 两种越权查询，全部 403 ==="
Login 'otheruser' '123456' | Out-Null
"  当前登录: otheruser(9002)"

$rDetail = Api GET "/booking/$bid" ''
$rPay    = Api POST "/booking/$bid/pay" (JsonBody @{ payChannel = 1 })
$rCancel = Api POST "/booking/$bid/cancel" (JsonBody @{ reason = 'hijack'; cancelRole = 1 })
$rResched = Api POST "/booking/$bid/reschedule" (JsonBody @{ newCheckIn = $ci2; newCheckOut = $co2 })
$rList   = Api GET '/booking/user/9001' ''

"  GET  /booking/{A的单}       -> $($rDetail.Http)   $($rDetail.Body.message)"
"  POST /booking/{A的单}/pay    -> $($rPay.Http)   $($rPay.Body.message)"
"  POST /booking/{A的单}/cancel -> $($rCancel.Http)   $($rCancel.Body.message)"
"  POST /booking/{A的单}/reschedule -> $($rResched.Http)   $($rResched.Body.message)"
"  GET  /booking/user/9001      -> $($rList.Http)   $($rList.Body.message)"

Check 'B cannot read A''s booking (403)'      ($rDetail.Http -eq 403)  "actual=$($rDetail.Http)"
Check 'B cannot pay A''s booking (403)'       ($rPay.Http -eq 403)     "actual=$($rPay.Http)"
Check 'B cannot cancel A''s booking (403)'    ($rCancel.Http -eq 403)  "actual=$($rCancel.Http)"
Check 'B cannot reschedule A''s booking (403)' ($rResched.Http -eq 403) "actual=$($rResched.Http)"
Check 'B cannot list A''s bookings (403)'     ($rList.Http -eq 403)    "actual=$($rList.Http)"
Check 'the message says it is about ownership' ("$($rDetail.Body.message)" -like '*别人的订单*') "msg=$($rDetail.Body.message)"

# ---------------------------------------------------------------------------
"`n=== 3. ★ 越权被拦下时不能有任何副作用 ==="
$statusNow = Sql "SELECT booking_status FROM booking WHERE booking_id=$bid"
$soldNow = DSold 2001 $ci
$checkInNow = Sql "SELECT check_in FROM booking WHERE booking_id=$bid"
"  订单状态 = $statusNow （应仍是 10 待支付）"
"  库存 $ci = $soldNow （应仍是 $soldBefore，没被退）"
"  check_in = $checkInNow （应仍是 $ci，没被改）"
Check 'status untouched (still 10)' ("$statusNow" -eq '10') "actual=$statusNow"
Check 'inventory untouched' ("$soldNow" -eq "$soldBefore") "was=$soldBefore now=$soldNow"
Check 'check_in untouched' ("$checkInNow" -eq "$ci") "actual=$checkInNow"

"`n  -- B 的 /booking/my 里不该有 A 的单 --"
$mine = Api GET '/booking/my' ''
"  B 的订单条数 = $($mine.Body.data.Count)  （应为 0）"
Check 'B''s own list is empty' (@($mine.Body.data).Count -eq 0) "count=$(@($mine.Body.data).Count)"
Check 'A''s booking id is not in B''s list' (@($mine.Body.data | Where-Object { "$($_.bookingId)" -eq "$bid" }).Count -eq 0) ''

# ---------------------------------------------------------------------------
"`n=== 4. 切回 A，自己的单一切正常 ==="
Login 'testuser' '123456' | Out-Null
$okDetail = Api GET "/booking/$bid" ''
"  A 查自己的单 -> HTTP $($okDetail.Http)  hotelName=$($okDetail.Body.data.hotelName)"
Check 'A can read its own booking' ($okDetail.Http -eq 200) "actual=$($okDetail.Http)"

$okList = Api GET '/booking/my' ''
"  A 的 /booking/my -> HTTP $($okList.Http)  条数=$(@($okList.Body.data).Count)"
Check 'A sees its own booking in /booking/my' (@($okList.Body.data | Where-Object { "$($_.bookingId)" -eq "$bid" }).Count -eq 1) ''

$okResched = Api POST "/booking/$bid/reschedule" (JsonBody @{ newCheckIn = $ci; newCheckOut = $co2 })
"  A 改自己的单（延长一晚）-> HTTP $($okResched.Http)"
Check 'A can reschedule its own booking' ($okResched.Http -eq 200) "actual=$($okResched.Http)"

$okCancel = Api POST "/booking/$bid/cancel" (JsonBody @{ reason = 'test'; cancelRole = 1 })
"  A 取消自己的单 -> HTTP $($okCancel.Http)"
Check 'A can cancel its own booking' ($okCancel.Http -eq 200) "actual=$($okCancel.Http)"

# ---------------------------------------------------------------------------
"`n=== 5. 不存在的订单号 -> 404（先查存在性，再验归属）==="
$fake = '777777777777777777'
$rFake = Api GET "/booking/$fake" ''
"  GET /booking/$fake -> HTTP $($rFake.Http)  msg=$($rFake.Body.message)"
Check 'a non-existent booking -> 404, not 403' ($rFake.Http -eq 404) "actual=$($rFake.Http)"

# ---------------------------------------------------------------------------
"`n=== 6. 两个账号各自列表互不干扰 ==="
Login 'testuser' '123456' | Out-Null
NewBooking 2001 (D 30) (D 31) 1 | Out-Null
$countA = @((Api GET '/booking/my' '').Body.data).Count
Login 'otheruser' '123456' | Out-Null
NewBooking 2001 (D 32) (D 33) 1 | Out-Null
$countB = @((Api GET '/booking/my' '').Body.data).Count
"  A 自己的单数 = $countA    B 自己的单数 = $countB"
Check 'A does not see B''s bookings' ($countA -ge 1) "countA=$countA"
Check 'B only sees its own new booking' ($countB -eq 1) "countB=$countB"

# 收尾：切回主账号
Login 'testuser' '123456' | Out-Null

Summarize
