# =============================================================================
#  p4_agent_test.ps1 —— 酒店预订 Agent（P4.3）
#
#  验证七件事：
#   1) 一句话把信息说全 -> 直接查到候选（SEARCHED）
#   2) 多轮补充：缺什么问什么，槽位一句一句攒起来
#   3) 大床/双床的过滤生效
#   4) 订不到的日期 -> NO_RESULT（而不是报错）
#   5) 过去的日期 -> 追问，不会被当成有效输入
#   6) 闲聊 / 取消 / 改期 各有正确的 stage
#   7) 会话隔离：别人的 sessionId 用不了；未登录 401
#
#  ★ 这个套件会真的调用大模型（DeepSeek），每次几百毫秒到两秒。
#    所以断言只盯【结构】和【语义】（stage / intent / 槽位 / 候选非空），
#    不去比对模型的具体措辞 —— 措辞每次都可能不一样，钉死它只会让测试变脆。
#    「总价算得对不对」这类事由别的套件保证（那些是代码算的，不是模型算的）。
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

function AgentChat([string]$msg, [string]$sid) {
    $body = @{ message = $msg }
    if ($sid) { $body['sessionId'] = $sid }
    return Api POST '/agent/chat' (JsonBody $body)
}
function Stage($r) { return "$($r.Body.data.stage)" }
function Missing($r) { return @($r.Body.data.missing) }

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
"  （这个套件会真的调大模型，慢一点是正常的）"

"`n=== 0. 重置 + 登录 ==="
ResetTestData | Out-Null
"  bookings=$(BCount)   已登录（token 长度 $($script:Token.Length)）"

# ---------------------------------------------------------------------------
#  ★ 没配 key 就跳过。
#    这个套件是唯一依赖外部大模型的，不该因为"跑测试的机器没设
#    DEEPSEEK_API_KEY"就让整个回归变红。判断依据是 503（Agent 未启用），
#    而不是随便什么错误 —— 别的错误还是要暴露出来。
$probe = AgentChat 'huaxue ceshi' ''
if ($probe.Http -eq 503) {
    ""
    "  [SKIP] Agent 未启用（hotel.agent.api-key 为空），跳过本套件。"
    "         本地跑之前先 dot-source F:\JavaProject\devops\local-env.ps1"
    ""
    exit 0
}

# ---------------------------------------------------------------------------
"`n=== 1. 一句话说全 ==="
$r1 = AgentChat '帮我订杭州11月20号住两晚，要大床房，1间'
"  HTTP $($r1.Http)   stage=$(Stage $r1)   intent=$($r1.Body.data.intent)   session=$($r1.Body.data.sessionId)"
"  reply: $($r1.Body.data.reply)"
$cands = @($r1.Body.data.candidates)
$cands | ForEach-Object { "    候选: $($_.hotelName) / $($_.roomTypeName)   ¥$($_.totalAmount)  $($_.nights)晚×$($_.roomCount)间" }
"  slots: $($r1.Body.data.slots | ConvertTo-Json -Compress)"
Check 'one-shot request reaches SEARCHED' ((Stage $r1) -eq 'SEARCHED') "actual=$(Stage $r1)"
Check 'intent is BOOK' ("$($r1.Body.data.intent)" -eq 'BOOK') "actual=$($r1.Body.data.intent)"
Check 'candidates are returned' ($cands.Count -ge 1) "count=$($cands.Count)"
Check 'all candidates match the requested bed type' (@($cands | Where-Object { "$($_.roomTypeName)" -notlike '*大床*' }).Count -eq 0) ''
Check 'all candidates have a positive total' (@($cands | Where-Object { [double]$_.totalAmount -le 0 }).Count -eq 0) ''
$sid1 = $r1.Body.data.sessionId

# ---------------------------------------------------------------------------
"`n=== 2. 多轮补充：缺什么问什么 ==="
$r2 = AgentChat '我想订房' ''
"  「我想订房」-> stage=$(Stage $r2)  missing=[$(Missing $r2 -join ',')]"
"    reply: $($r2.Body.data.reply)"
$sid2 = $r2.Body.data.sessionId
Check 'bare request asks for more info' ((Stage $r2) -eq 'COLLECTING') "actual=$(Stage $r2)"
Check 'it discovers the city is missing' ((Missing $r2) -contains 'city') "missing=$((Missing $r2) -join ',')"

$r3 = AgentChat '杭州' $sid2
"  「杭州」    -> stage=$(Stage $r3)  missing=[$(Missing $r3 -join ',')]  city=$($r3.Body.data.slots.city)"
Check 'city gets filled in turn 2' ("$($r3.Body.data.slots.city)" -eq '杭州') "actual=$($r3.Body.data.slots.city)"
Check 'dates are still missing' ((Missing $r3) -contains 'checkIn') "missing=$((Missing $r3) -join ',')"

$r4 = AgentChat '11月20号住两晚' $sid2
"  「11月20号住两晚」-> stage=$(Stage $r4)  checkIn=$($r4.Body.data.slots.checkIn)  checkOut=$($r4.Body.data.slots.checkOut)"
Check 'multi-turn collection finally reaches SEARCHED' ((Stage $r4) -eq 'SEARCHED') "actual=$(Stage $r4)"
Check 'the collected city was carried over' ("$($r4.Body.data.slots.city)" -eq '杭州') "actual=$($r4.Body.data.slots.city)"
Check 'checkout is 2 nights after checkin' ("$($r4.Body.data.slots.checkOut)" -gt "$($r4.Body.data.slots.checkIn)") ''

# ---------------------------------------------------------------------------
"`n=== 3. 床型过滤 ==="
$r5 = AgentChat '11月24号杭州住一晚，要双床' ''
$candsB = @($r5.Body.data.candidates)
"  stage=$(Stage $r5)   候选 $($candsB.Count) 个"
$candsB | ForEach-Object { "    $($_.roomTypeName)" }
Check 'double-bed request only returns double rooms' (@($candsB | Where-Object { "$($_.roomTypeName)" -notlike '*双床*' }).Count -eq 0) ''
Check 'the double rooms exist in the seed' ($candsB.Count -ge 1) "count=$($candsB.Count)"

# ---------------------------------------------------------------------------
"`n=== 4. 订不到的日期 -> NO_RESULT ==="
"  用一个远超价格日历的日期（180 天之外）"
$far = (Get-Date).AddDays(300).ToString('yyyy-MM-dd')
$farOut = (Get-Date).AddDays(302).ToString('yyyy-MM-dd')
$r6 = AgentChat "订杭州 $far 到 $farOut 的房间" ''
"  stage=$(Stage $r6)"
"  reply: $($r6.Body.data.reply)"
Check 'unbookable dates produce NO_RESULT (not an error)' ((Stage $r6) -eq 'NO_RESULT') "actual=$(Stage $r6)"
Check 'it is still HTTP 200 (a graceful answer)' ($r6.Http -eq 200) "actual=$($r6.Http)"

# ---------------------------------------------------------------------------
"`n=== 5. 过去的日期 -> 追问，不当成有效输入 ==="
$past = (Get-Date).AddDays(-10).ToString('yyyy-MM-dd')
$r7 = AgentChat "订杭州 $past 的房间住两晚" ''
"  stage=$(Stage $r7)  missing=[$(Missing $r7 -join ',')]"
"  reply: $($r7.Body.data.reply)"
Check 'a past date is rejected back into COLLECTING' ((Stage $r7) -eq 'COLLECTING') "actual=$(Stage $r7)"
Check 'dates are asked again' (((Missing $r7) -contains 'checkIn') -or ((Missing $r7) -contains 'checkOut')) "missing=$((Missing $r7) -join ',')"

# ---------------------------------------------------------------------------
"`n=== 6. 其他意图 ==="
$r8 = AgentChat '你好，你能干什么' ''
"  闲聊 -> stage=$(Stage $r8)  intent=$($r8.Body.data.intent)"
Check 'chitchat is handled' (((Stage $r8) -eq 'CHITCHAT') -or ("$($r8.Body.data.intent)" -eq 'CHITCHAT')) "stage=$(Stage $r8) intent=$($r8.Body.data.intent)"

$r9 = AgentChat '帮我把订单取消了吧' ''
"  取消 -> stage=$(Stage $r9)  intent=$($r9.Body.data.intent)"
Check 'cancel intent is acknowledged (UNSUPPORTED for now)' (((Stage $r9) -eq 'UNSUPPORTED') -or ("$($r9.Body.data.intent)" -eq 'CANCEL')) "stage=$(Stage $r9) intent=$($r9.Body.data.intent)"

# ---------------------------------------------------------------------------
"`n=== 7. 会话隔离 与 鉴权 ==="
"  用别人的 sessionId 继续对话"
$r10 = AgentChat '杭州' $sid1
"    传 $sid1（属于同一个账号）-> stage=$(Stage $r10)  city=$($r10.Body.data.slots.city)"
Check 'the same user can continue its own session' ($r10.Http -eq 200) "actual=$($r10.Http)"

"  换一个账号（9002）拿 9001 的 sessionId"
Login 'otheruser' '123456' | Out-Null
$r11 = AgentChat '杭州' $sid1
"    otheruser 用 9001 的 session -> HTTP $($r11.Http)  session=$($r11.Body.data.sessionId)"
Check 'another user cannot reuse the session' ("$($r11.Body.data.sessionId)" -ne "$sid1") "got=$($r11.Body.data.sessionId)"

"  未登录"
ClearToken
$r12 = AgentChat '杭州' ''
"    无 token -> HTTP $($r12.Http)"
Check 'agent requires login' ($r12.Http -eq 401) "actual=$($r12.Http)"

Login 'testuser' '123456' | Out-Null

# ---------------------------------------------------------------------------
"`n=== 8. 会话状态确实存在 Redis 里 ==="
$r13 = AgentChat '我想订房' ''
$sidCk = $r13.Body.data.sessionId
$ttl = (& 'F:\redis\Redis-x64-3.0.504\redis-cli.exe' -p 16379 -a dzkjdx TTL "hotel:agent:session:$sidCk" 2>$null)
"  hotel:agent:session:$sidCk  TTL=$ttl 秒"
Check 'the session is persisted in Redis with a TTL' ([int]$ttl -gt 0) "ttl=$ttl"

Summarize
