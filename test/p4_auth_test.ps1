# =============================================================================
#  p4_auth_test.ps1 —— 登录 / token / 拦截器（P4.1）
#
#  验证七件事：
#   1) 正确的用户名密码能登录，拿到 token
#   2) 密码错 / 用户不存在 -> 401，而且两者提示【一模一样】（防用户名枚举）
#   3) 不带 token 访问业务接口 -> 401
#   4) 带一个瞎编的 token 访问 -> 401
#   5) 带正确 token -> 200，且 /auth/me 能说出"我是谁"
#   6) ★ 下单用的 userId 来自 token，【请求体里的 userId 被无视】
#   7) 退出登录后 token 立刻失效
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$ci = D 10
$co = D 12

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
"  种子账号: testuser / otheruser（密码都是 123456）"

"`n=== 0. 重置（会清掉 token，ResetTestData 会重新登录）==="
ResetTestData | Out-Null
"  bookings=$(BCount)   当前 token 长度=$(if ($script:Token) { $script:Token.Length } else { 0 })"
Check 'ResetTestData leaves a usable token behind' (($script:Token) -and ($script:Token.Length -gt 10)) ''

# ---------------------------------------------------------------------------
"`n=== 1. 正常登录 ==="
ClearToken
$ok = Login 'testuser' '123456'
"  POST /auth/login -> HTTP $($ok.Http)"
"    userId=$($ok.Body.data.userId)  username=$($ok.Body.data.username)  role=$($ok.Body.data.role)"
"    token=$($ok.Body.data.token)  有效期=$($ok.Body.data.expiresInSeconds)s"
Check 'login succeeds with correct password' ($ok.Http -eq 200) "actual=$($ok.Http)"
Check 'login returns the right user' ($ok.Body.data.userId -eq 9001) "actual=$($ok.Body.data.userId)"
Check 'login returns a 32-char token' ($ok.Body.data.token.Length -eq 32) "len=$($ok.Body.data.token.Length)"

"  Redis 里的会话: TTL=$((& 'F:\redis\Redis-x64-3.0.504\redis-cli.exe' -p 16379 -a dzkjdx TTL "hotel:token:$($ok.Body.data.token)" 2>$null)) 秒"

# ---------------------------------------------------------------------------
"`n=== 2. 密码错 / 用户不存在：提示必须一样（防用户名枚举）==="
$badPwd = Api POST '/auth/login' (JsonBody @{ username = 'testuser'; password = 'wrong-password' })
$noUser = Api POST '/auth/login' (JsonBody @{ username = 'no-such-user'; password = '123456' })
"  密码错       -> HTTP $($badPwd.Http)  msg=$($badPwd.Body.message)"
"  用户不存在   -> HTTP $($noUser.Http)  msg=$($noUser.Body.message)"
Check 'wrong password -> 401' ($badPwd.Http -eq 401) "actual=$($badPwd.Http)"
Check 'unknown user -> 401' ($noUser.Http -eq 401) "actual=$($noUser.Http)"
Check 'the two messages are identical (no user enumeration)' ("$($badPwd.Body.message)" -eq "$($noUser.Body.message)") "differs"

# ---------------------------------------------------------------------------
"`n=== 3. 不带 token 访问业务接口 -> 401 ==="
ClearToken
$noToken = Api GET '/booking/availability?roomTypeId=2001' ''
"  GET /booking/availability（无 token）-> HTTP $($noToken.Http)  msg=$($noToken.Body.message)"
Check 'business API without token -> 401' ($noToken.Http -eq 401) "actual=$($noToken.Http)"
Check 'the 401 body is in the standard shape' ($noToken.Body.code -eq 401) "code=$($noToken.Body.code)"

$noTokenPost = Api POST '/booking/quote' (JsonBody @{ roomTypeId = 2001; checkIn = $ci; checkOut = $co; roomCount = 1 })
"  POST /booking/quote（无 token）-> HTTP $($noTokenPost.Http)"
Check 'POST without token -> 401' ($noTokenPost.Http -eq 401) "actual=$($noTokenPost.Http)"

"`n  -- 登录接口本身当然不需要 token（已放行）--"
$loginNoToken = Api POST '/auth/login' (JsonBody @{ username = 'testuser'; password = '123456' })
"  POST /auth/login（无 token）-> HTTP $($loginNoToken.Http)"
Check 'login itself is whitelisted' ($loginNoToken.Http -eq 200) "actual=$($loginNoToken.Http)"

# ---------------------------------------------------------------------------
"`n=== 4. 瞎编的 token -> 401 ==="
$script:Token = 'this-is-not-a-real-token'
$fake = Api GET '/booking/my' ''
"  带瞎编 token -> HTTP $($fake.Http)  msg=$($fake.Body.message)"
Check 'a forged token -> 401' ($fake.Http -eq 401) "actual=$($fake.Http)"

# ---------------------------------------------------------------------------
"`n=== 5. 正确 token -> 200，且 /auth/me 说得出我是谁 ==="
Login 'testuser' '123456' | Out-Null
$me = Api GET '/auth/me' ''
"  GET /auth/me -> HTTP $($me.Http)  userId=$($me.Body.data.userId)  username=$($me.Body.data.username)  role=$($me.Body.data.role)"
Check '/auth/me works with a valid token' ($me.Http -eq 200) ''
Check '/auth/me returns my own identity' ($me.Body.data.userId -eq 9001) ''
Check '/auth/me returns the username' ("$($me.Body.data.username)" -eq 'testuser') ''

# ---------------------------------------------------------------------------
"`n=== 6. ★ 下单的 userId 来自 token，请求体里的 userId 被无视 ==="
# 先用 9001 登录，但请求体里故意塞 9002 —— 如果服务端还读请求体，订单就记到 9002 名下
$body = JsonBody @{
    userId     = 9002          # ← 恶意/错误的值，应该被忽略
    hotelId    = 1001
    roomTypeId = 2001
    checkIn    = $ci
    checkOut   = $co
    roomCount  = 1
    guestCount = 1
}
$c = Api POST '/booking' $body
$newId = $c.Body.data
"  POST /booking（token=9001，body 里写 9002）-> HTTP $($c.Http)  bookingId=$newId"
$ownerInDb = Sql "SELECT user_id FROM booking WHERE booking_id=$newId"
"  DB 里这笔订单的 user_id = $ownerInDb  （必须是 9001）"
Check 'booking was created' ($c.Http -eq 200) "actual=$($c.Http)"
Check 'the body userId was IGNORED, token user won' ("$ownerInDb" -eq '9001') "actual=$ownerInDb"

"`n  -- /booking/my 只返回我自己的 --"
$mine = Api GET '/booking/my' ''
"  GET /booking/my -> HTTP $($mine.Http)  条数=$($mine.Body.data.Count)"
$foreign = @($mine.Body.data | Where-Object { "$($_.userId)" -ne '9001' }).Count
Check 'my list contains no other user''s bookings' ($foreign -eq 0) "foreign=$foreign"

# ---------------------------------------------------------------------------
"`n=== 7. 退出登录后 token 立刻失效 ==="
$tok = $script:Token
$lo = Api POST '/auth/logout' ''
"  POST /auth/logout -> HTTP $($lo.Http)  loggedOut=$($lo.Body.data.loggedOut)"
Check 'logout succeeds' ($lo.Http -eq 200) ''
"  退出后那个 token 还在 Redis 里吗: TTL=$((& 'F:\redis\Redis-x64-3.0.504\redis-cli.exe' -p 16379 -a dzkjdx TTL "hotel:token:$tok" 2>$null))（-2 = 已删除）"

$script:Token = $tok
$after = Api GET '/auth/me' ''
"  用退出后的 token 再访问 -> HTTP $($after.Http)"
Check 'the token is dead right after logout' ($after.Http -eq 401) "actual=$($after.Http)"

"`n  -- 重新登录恢复可用 --"
Login 'testuser' '123456' | Out-Null
$again = Api GET '/auth/me' ''
Check 'logging in again works' ($again.Http -eq 200) "actual=$($again.Http)"

# ---------------------------------------------------------------------------
"`n=== 8. 另一个账号也能登录，且身份独立 ==="
$other = Login 'otheruser' '123456'
"  otheruser 登录 -> HTTP $($other.Http)  userId=$($other.Body.data.userId)"
Check 'the second seed user can log in' ($other.Http -eq 200) ''
Check 'it gets its own userId' ($other.Body.data.userId -eq 9002) ''
$meOther = Api GET '/auth/me' ''
"  otheruser 的 /auth/me -> userId=$($meOther.Body.data.userId)"
Check 'the second user sees its own identity' ($meOther.Body.data.userId -eq 9002) ''

# 收尾：切回主账号，别影响后面的套件
Login 'testuser' '123456' | Out-Null

Summarize
