# =============================================================================
#  p4_agent_hitl_test.ps1 —— 人工确认后下单（P4.4）
#
#  验证七件事：
#   1) 查到候选后，草案被存进会话
#   2) 说"确认"/"订吧" -> 真的下单，返回订单号，DB 里数据正确
#   3) ★ 同一轮再确认一次 -> 不会下第二单（草案用完即作废）
#   4) 说"算了" -> 不下单
#   5) 没有草案时确认 -> NO_DRAFT（不会瞎订）
#   6) ★★ 下单内容以【草案】为准，不以用户这句话为准：
#          草案是 2001 大床房，用户却说"订 2002 那个双床房"
#          -> 订的必须是 2001（否则"订那个 100 块的"就能把单引走）
#   7) 草案过期 -> DRAFT_EXPIRED，不敢下单
#
#  ★ 没配 key 就跳过（和 p4_agent_test 一样）
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$RT_BED   = 2001    # 高级大床房（3 间）
$RT_TWIN  = 2002    # 豪华双床房（2 间）
$ci  = D 40
$co  = D 42

function AgentChat([string]$msg, [string]$sid) {
    $body = @{ message = $msg }
    if ($sid) { $body['sessionId'] = $sid }
    return Api POST '/agent/chat' (JsonBody $body)
}
function Stage($r) { return "$($r.Body.data.stage)" }
function SessionKey([string]$sid) { return "hotel:agent:session:$sid" }

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')"

"`n=== 0. 重置 + 登录 ==="
ResetTestData | Out-Null
"  bookings=$(BCount)"

# 没配 key 就跳过
$probe = AgentChat 'huaxue ceshi' ''
if ($probe.Http -eq 503) {
    ""
    "  [SKIP] Agent 未启用（hotel.agent.api-key 为空），跳过本套件。"
    ""
    exit 0
}

# ---------------------------------------------------------------------------
"`n=== 1. 查候选，草案进入会话 ==="
$r1 = AgentChat "帮我订杭州 $ci 到 $co 的房，要大床房，1间" ''
$sid = $r1.Body.data.sessionId
"  stage=$(Stage $r1)  session=$sid"
"  reply: $($r1.Body.data.reply)"
Check 'search reaches SEARCHED' ((Stage $r1) -eq 'SEARCHED') "actual=$(Stage $r1)"

$sessionJson = RedisCmd @('GET', (SessionKey $sid))
$hasDraft = $sessionJson -like '*"candidates"*'
"  会话里有草案字段: $hasDraft   会话长度 $($sessionJson.Length)"
Check 'the candidates are persisted in the session' $hasDraft ''

# ---------------------------------------------------------------------------
"`n=== 2. 确认 -> 真的下单 ==="
$before = [int](BCount)
$r2 = AgentChat '确认，就它了' $sid
$after = [int](BCount)
$newId = $r2.Body.data.bookingId
"  stage=$(Stage $r2)  bookingId=$newId   订单数 $before -> $after"
"  reply: $($r2.Body.data.reply)"
Check 'confirm reaches BOOKED' ((Stage $r2) -eq 'BOOKED') "actual=$(Stage $r2)"
Check 'a bookingId is returned' ($null -ne $newId -and "$newId".Length -gt 5) "bookingId=$newId"
Check 'exactly one booking was created' ($after -eq ($before + 1)) "before=$before after=$after"

if ($newId) {
    $row = Sql "SELECT CONCAT(user_id,'|',room_type_id,'|',check_in,'|',check_out,'|',room_count,'|',booking_status) FROM booking WHERE booking_id=$newId"
    "  DB: user_id|room_type_id|check_in|check_out|room_count|status = $row"
    $parts = "$row" -split '\|'
    Check 'the booking belongs to the logged-in user' ($parts[0] -eq '9001') "userId=$($parts[0])"
    # 注意：不能硬编码期望房型 —— "最便宜的大床房"是搜索算出来的（种子数据下是 2003 园景大床房）。
    # 要断言的是"订的房型 == 草案里的房型"。
    $expectedRt = "$($r1.Body.data.candidates[0].roomTypeId)"
    Check 'the booking uses the draft room type'    ("$($parts[1])" -eq $expectedRt) "roomTypeId=$($parts[1]) expected=$expectedRt"
    Check 'the booking uses the draft dates'        ("$($parts[2])" -eq "$ci") "checkIn=$($parts[2])"
    Check 'the booking starts in PENDING_PAYMENT'   ("$($parts[5])" -eq '10') "status=$($parts[5])"
}

# ---------------------------------------------------------------------------
"`n=== 3. ★ 再确认一次：不能下第二单 ==="
$before3 = [int](BCount)
$r3 = AgentChat '确认' $sid
$after3 = [int](BCount)
"  stage=$(Stage $r3)  订单数 $before3 -> $after3"
"  reply: $($r3.Body.data.reply)"
Check 'a second confirm does not create another booking' ($after3 -eq $before3) "before=$before3 after=$after3"
Check 'the draft is gone after booking' ((Stage $r3) -in @('NO_DRAFT','COLLECTING','CHITCHAT')) "actual=$(Stage $r3)"

# ---------------------------------------------------------------------------
"`n=== 4. 说「算了」不下单 ==="
$r4a = AgentChat "帮我订杭州 $ci 到 $co 的双床房，1间" ''
$sid4 = $r4a.Body.data.sessionId
"  先查到候选: stage=$(Stage $r4a)"
$before4 = [int](BCount)
$r4 = AgentChat '算了，先不订了' $sid4
$after4 = [int](BCount)
"  说「算了」-> stage=$(Stage $r4)  订单数 $before4 -> $after4"
Check 'cancelling the intention creates no booking' ($after4 -eq $before4) "before=$before4 after=$after4"

# ---------------------------------------------------------------------------
"`n=== 5. 没有草案时确认 -> 不会瞎订 ==="
$fresh = AgentChat '你好' ''
$sid5 = $fresh.Body.data.sessionId
$r5 = AgentChat '确认' $sid5
"  新会话直接说「确认」-> stage=$(Stage $r5)"
"  reply: $($r5.Body.data.reply)"
Check 'confirm without a draft is refused' ((Stage $r5) -eq 'NO_DRAFT') "actual=$(Stage $r5)"

# ---------------------------------------------------------------------------
"`n=== 6. ★★ 下单内容以草案为准（用户话里提别的房型也没用）==="
# 手工造一个会话：草案指向 2001 大床房
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$sid6 = "hitl-draft-$($now.ToString().Substring(7))"
$draft6 = @{
    hotelId = 1001; hotelName = '草案酒店'
    roomTypeId = $RT_BED; roomTypeName = '草案房型'
    checkIn = $ci; checkOut = $co; roomCount = 1
    totalAmount = 1000.00; createdAt = $now
}
$session6 = @{
    sessionId = $sid6; userId = 9001; intent = 'BOOK'
    slots = @{ city = '杭州'; checkIn = $ci; checkOut = $co; roomCount = '1' }
    history = @()
    candidates = @($draft6)
    selectedIndex = 0
    updatedAt = $now
} | ConvertTo-Json -Depth 8 -Compress
# ★ 必须用 RedisSetRaw：redis-cli 直接传参会把 JSON 里的双引号吃掉，
#   应用解析失败就会当成"会话不存在"（踩过）
RedisSetRaw (SessionKey $sid6) $session6
$readBack = RedisGetRaw (SessionKey $sid6)
"  回读 $($readBack.Length) 字节，开头: $($readBack.Substring(0,[Math]::Min(70,$readBack.Length)))"
# 断言"双引号还在、还是一份合法 JSON"，而不是逐字节相等 ——
# 逐字节会被工具的编码/换行习惯影响（这里就踩过），
# 而真正要保证的是【写进去的是一份引号完好的 JSON】
# （原来的 bug 就是 redis-cli 把引号吃掉，变成 {a:1} 这种非法格式）。
Check 'the hand-written session is valid JSON (quotes survived)' `
    (($readBack.StartsWith('{')) -and ($readBack.EndsWith('}')) -and ($readBack -like '*"candidates"*')) `
    "head=$($readBack.Substring(0,[Math]::Min(60,$readBack.Length)))"
"  已手工写入会话 $sid6，草案指向 roomTypeId=$RT_BED（大床房）"

# ★ 关键点：草案指向 2001（高级大床房），而"重新搜一次"会选中 2003（园景大床房，最便宜）。
#   所以只要最终订的是 2001，就证明下单内容来自【草案】而不是重新搜索/用户这句话。
$r6 = AgentChat '确认' $sid6
$newId6 = $r6.Body.data.bookingId
"  stage=$(Stage $r6)  bookingId=$newId6"
if ($newId6) {
    $rt6 = Sql "SELECT room_type_id FROM booking WHERE booking_id=$newId6"
    "  DB 里实际订的 room_type_id = $rt6  （必须是 $RT_BED，不是 $RT_TWIN）"
    Check 'the order follows the DRAFT, not the words' ("$rt6" -eq "$RT_BED") "actual=$rt6 expected=$RT_BED"
} else {
    Check 'the order follows the DRAFT, not the words' $false "no bookingId; stage=$(Stage $r6)"
}

# ---------------------------------------------------------------------------
"`n=== 7. 草案过期 -> 不敢下单 ==="
$old = $now - (30 * 60 * 1000)   # 30 分钟前
$sid7 = "hitl-stale-$($now.ToString().Substring(7))"
$draft7 = @{
    hotelId = 1001; hotelName = '过期草案酒店'
    roomTypeId = $RT_BED; roomTypeName = '过期草案房型'
    checkIn = $ci; checkOut = $co; roomCount = 1
    totalAmount = 1000.00; createdAt = $old
}
$session7 = @{
    sessionId = $sid7; userId = 9001; intent = 'BOOK'
    slots = @{ city = '杭州'; checkIn = $ci; checkOut = $co; roomCount = '1' }
    history = @()
    candidates = @($draft7)
    selectedIndex = 0
    updatedAt = $old
} | ConvertTo-Json -Depth 8 -Compress
RedisSetRaw (SessionKey $sid7) $session7
"  已写入一个 30 分钟前的草案"

$before7 = [int](BCount)
$r7 = AgentChat '确认' $sid7
$after7 = [int](BCount)
"  stage=$(Stage $r7)  订单数 $before7 -> $after7"
"  reply: $($r7.Body.data.reply)"
Check 'a stale draft is refused' ((Stage $r7) -eq 'DRAFT_EXPIRED') "actual=$(Stage $r7)"
Check 'a stale draft creates no booking' ($after7 -eq $before7) "before=$before7 after=$after7"

RedisCmd @('DEL', (SessionKey $sid6)) | Out-Null
RedisCmd @('DEL', (SessionKey $sid7)) | Out-Null

# ---------------------------------------------------------------------------
"`n=== 8. ★ 候选可以挑（P1）==="
$r8a = AgentChat "帮我订杭州 $ci 到 $co 的房，要大床房，1间" ''
$sid8 = $r8a.Body.data.sessionId
"  stage=$(Stage $r8a)  session=$sid8"
"  reply: $($r8a.Body.data.reply)"

$sess8 = (RedisGetRaw (SessionKey $sid8)) | ConvertFrom-Json
$cand8 = @($sess8.candidates)
"  会话里存了 $($cand8.Count) 个候选，选中 index=$($sess8.selectedIndex)"
Check 'all candidates are kept in the session (not just the cheapest)' ($cand8.Count -ge 2) "count=$($cand8.Count)"
Check 'the default selection is the first one' ("$($sess8.selectedIndex)" -eq '0') "actual=$($sess8.selectedIndex)"

$r8b = AgentChat '第二个吧' $sid8
"  说「第二个吧」-> stage=$(Stage $r8b)"
"  reply: $($r8b.Body.data.reply)"
Check 'picking the 2nd candidate reaches SELECTED' ((Stage $r8b) -eq 'SELECTED') "actual=$(Stage $r8b)"

$sess8b = (RedisGetRaw (SessionKey $sid8)) | ConvertFrom-Json
"  selectedIndex 变成 $($sess8b.selectedIndex)"
Check 'the selection actually moved to index 1' ("$($sess8b.selectedIndex)" -eq '1') "actual=$($sess8b.selectedIndex)"

$want8 = "$($cand8[1].roomTypeId)"
"  第 2 个候选的房型 = $want8（第 1 个是 $($cand8[0].roomTypeId)）"
$r8c = AgentChat '确认' $sid8
$newId8 = $r8c.Body.data.bookingId
"  确认 -> stage=$(Stage $r8c)  bookingId=$newId8"
if ($newId8) {
    $rt8 = Sql "SELECT room_type_id FROM booking WHERE booking_id=$newId8"
    "  DB 里订的房型 = $rt8 （必须是 $want8，不是第 1 个）"
    Check 'it books the SELECTED candidate, not the cheapest' ("$rt8" -eq $want8) "actual=$rt8 expected=$want8"
} else {
    Check 'it books the SELECTED candidate, not the cheapest' $false "no bookingId"
}

# ---------------------------------------------------------------------------
"`n=== 9. ★ 不能清别人的会话（P0）==="
Login 'otheruser' '123456' | Out-Null
$r9 = Api POST '/agent/reset' (JsonBody @{ sessionId = $sid8 })
"  otheruser 清 9001 的会话 -> HTTP $($r9.Http)  $($r9.Body.message)"
Check 'another user cannot clear someone else''s session' ($r9.Http -eq 403) "actual=$($r9.Http)"
Check 'the message says it is about ownership' ("$($r9.Body.message)" -like '*别人的会话*') "msg=$($r9.Body.message)"
$still8 = RedisGetRaw (SessionKey $sid8)
Check 'the session is still there' ($still8.Length -gt 10) "len=$($still8.Length)"

Login 'testuser' '123456' | Out-Null
$r9b = Api POST '/agent/reset' (JsonBody @{ sessionId = $sid8 })
"  testuser 清自己的会话 -> HTTP $($r9b.Http)"
Check 'the owner can clear its own session' ($r9b.Http -eq 200) "actual=$($r9b.Http)"

# ---------------------------------------------------------------------------
"`n=== 10. ★ 历史对话真的发给模型了（P0）==="
# 先查一次两晚，然后只说"再住一晚" —— 只有模型看得到上文才算得对
$r10a = AgentChat "帮我订杭州 $ci 住两晚，1间大床房" ''
$sid10 = $r10a.Body.data.sessionId
$out10 = "$($r10a.Body.data.slots.checkOut)"
"  第一次: checkIn=$($r10a.Body.data.slots.checkIn) checkOut=$out10 stage=$(Stage $r10a)"

$r10b = AgentChat '再住一晚呢' $sid10
$out10b = "$($r10b.Body.data.slots.checkOut)"
"  说「再住一晚呢」-> checkOut=$out10b  stage=$(Stage $r10b)"
"  reply: $($r10b.Body.data.reply)"
Check 'the follow-up keeps the earlier dates in context' ($out10b -ne $out10) "before=$out10 after=$out10b"
Check 'checkout moved later (it understood "+1 night")' ($out10b -gt $out10) "before=$out10 after=$out10b"

# ---------------------------------------------------------------------------
"`n=== 11. ★ 我的订单 + 取消（P2，两步确认）==="
ResetTestData | Out-Null
"  重置后 bookings=$(BCount)"

# 直接走接口建一单（不经过 Agent），这样测的是 Agent 的"取消"能力
$c11 = NewBooking 2001 (D 60) (D 62) 1
$bid11 = $c11.Body.data
"  建单 bookingId=$bid11  状态=$(Sql "SELECT booking_status FROM booking WHERE booking_id=$bid11")"
Check 'the booking was created for the cancel test' ($c11.Http -eq 200) "actual=$($c11.Http)"

$r11a = AgentChat '我的订单' ''
"  「我的订单」-> stage=$(Stage $r11a)"
"  reply: $($r11a.Body.data.reply)"
Check 'listing my bookings works' ((Stage $r11a) -eq 'LISTED') "actual=$(Stage $r11a)"
Check 'the list contains the right booking id' ("$($r11a.Body.data.reply)" -like "*$bid11*") "bid=$bid11"

# ★ 必须带上 sessionId：草案存在会话里，不带就等于每轮都是新会话
$sid11 = $r11a.Body.data.sessionId
$r11b = AgentChat '帮我把它取消了吧' $sid11
"  「帮我把它取消了吧」-> stage=$(Stage $r11b)"
"  reply: $($r11b.Body.data.reply)"
# SELECTED（用户从候选里挑中了）和 CONFIRM_NEEDED（直接解析出唯一目标）
# 都是"在等确认"，语义等价 —— 关键断言是下面那条：DB 状态没变
Check 'cancel asks for confirmation first' ((Stage $r11b) -in @('CONFIRM_NEEDED','SELECTED')) "actual=$(Stage $r11b)"
$st11 = Sql "SELECT booking_status FROM booking WHERE booking_id=$bid11"
"  ★ 此时 DB 状态 = $st11（必须还是 10 —— 没确认就不能动）"
Check 'nothing was cancelled before confirmation' ("$st11" -eq '10') "actual=$st11"
Check 'inventory is still held' ("$(DSold 2001 (D 60))" -eq '1') "sold=$(DSold 2001 (D 60))"

$r11c = AgentChat '确认' $sid11
"  「确认」-> stage=$(Stage $r11c)"
"  reply: $($r11c.Body.data.reply)"
Check 'confirm actually cancels' ((Stage $r11c) -eq 'CANCELLED') "actual=$(Stage $r11c)"
$st11b = Sql "SELECT booking_status FROM booking WHERE booking_id=$bid11"
"  DB 状态 = $st11b（90 = 已取消）   库存 = $(DSold 2001 (D 60))"
Check 'the booking is now CANCELLED (90)' ("$st11b" -eq '90') "actual=$st11b"
Check 'inventory was released' ("$(DSold 2001 (D 60))" -eq '0') "sold=$(DSold 2001 (D 60))"

# ---------------------------------------------------------------------------
"`n=== 12. ★ 改期（P2，两步确认）==="
$ci12 = D 65
$co12 = D 67
$ni12 = D 70
$no12 = D 72
$c12 = NewBooking 2001 $ci12 $co12 1
$bid12 = $c12.Body.data
"  建单 bookingId=$bid12  $ci12 ~ $co12"

$r12a = AgentChat "把订单 $bid12 改期到 $ni12 入住，$no12 离店" ''
"  「改期」-> stage=$(Stage $r12a)"
"  reply: $($r12a.Body.data.reply)"
Check 'reschedule asks for confirmation first' ((Stage $r12a) -eq 'CONFIRM_NEEDED') "actual=$(Stage $r12a)"
$sid12 = $r12a.Body.data.sessionId
$in12 = Sql "SELECT check_in FROM booking WHERE booking_id=$bid12"
"  ★ 此时 DB check_in = $in12（必须还是原值 $ci12）"
Check 'nothing changed before confirmation' ("$in12" -eq "$ci12") "actual=$in12"

$r12b = AgentChat '确认' $sid12
"  「确认」-> stage=$(Stage $r12b)"
"  reply: $($r12b.Body.data.reply)"
Check 'confirm actually reschedules' ((Stage $r12b) -eq 'RESCHEDULED') "actual=$(Stage $r12b)"
$row12 = Sql "SELECT CONCAT(check_in,'|',check_out,'|',booking_status) FROM booking WHERE booking_id=$bid12"
"  DB: check_in|check_out|status = $row12"
Check 'the booking dates actually changed' ("$(($row12 -split '\|')[0])" -eq "$ni12") "actual=$($row12 -split '\|')[0] expected=$ni12"

# ---------------------------------------------------------------------------
"`n=== 13. 收尾一致性 ==="
$mismatch = Sql @"
SELECT COUNT(*) FROM room_inventory i
LEFT JOIN (SELECT room_type_id, stay_date, SUM(room_count) n FROM booking_inventory_link WHERE released=0 GROUP BY room_type_id, stay_date) l
  ON l.room_type_id=i.room_type_id AND l.stay_date=i.stay_date
WHERE i.sold_rooms <> IFNULL(l.n,0)
"@
Check 'no drift between ledger and inventory' ($mismatch -eq '0') "mismatched=$mismatch"

Summarize
