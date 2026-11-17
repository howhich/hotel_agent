# =============================================================================
#  p6_multi_instance_test.ps1 —— 双实例（8080 + 8081）并发正确性
#
#  为什么要有这个：项目一直是单实例跑的，很多"分布式"的设计（原子认领、
#  分布式锁、幂等、雪花 ID）其实【从没在并发下被真实验证过】——
#  单实例下它们不出问题，可能只是因为压根没并发。
#
#  这个套件回答三个问题：
#    ① 两个实例同时下单，bookingId 会不会撞？（雪花 ID 的 workerId 同机会不会重复）
#    ② 超时关单会不会被两个实例各释放一次？（原子认领到底有没有用）
#    ③ 在 8080 登录拿到的 token，8081 认不认？（会话是否共享）
#
#  ★ 前提：先起两个实例
#       mvn spring-boot:run                                    # 8080
#       mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
#    只起一个实例时，本套件会自动跳过（打印 [SKIP] 退出 0）。
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

# ★ Windows PowerShell 5.1 默认不加载 System.Net.Http 程序集，
#   直接 New-Object System.Net.Http.HttpClient 会报
#   "找不到类型 [System.Net.Http.HttpClient]"。
#   必须显式 Add-Type 一次。
Add-Type -AssemblyName System.Net.Http

$P1 = 'http://127.0.0.1:8080'
$P2 = 'http://127.0.0.1:8081'

function PortAlive([string]$p) {
    try {
        Invoke-WebRequest -Method POST -Uri "$p/auth/login" -ContentType 'application/json' `
            -Body '{"username":"testuser","password":"123456"}' -UseBasicParsing -TimeoutSec 5 | Out-Null
        return $true
    } catch { return $false }
}

"=== 双实例并发正确性 ==="
"  今天 = $((Get-Date).ToString('yyyy-MM-dd HH:mm'))"
"  8080 = $(PortAlive $P1)   8081 = $(PortAlive $P2)"
if (-not (PortAlive $P2)) {
    ""
    "  [SKIP] 只起了 8080 一个实例。第二个实例起来后再跑："
    "         mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081"
    ""
    exit 0
}

ResetTestData | Out-Null
"  测试数据已重置，bookings=$(BCount)"

# ---------- 工具：向指定端口发请求（真并发，用 HttpClient）----------
"`n=== 1. ★ 双实例并发下单：bookingId 会不会撞 ==="
# 每个请求用【互不重叠】的日期窗口，避免房间数不够导致 409
$ports = @()
$windows = @()
for ($i = 0; $i -lt 12; $i++) {
    $ports += $(if ($i % 2 -eq 0) { $P1 } else { $P2 })
    $windows += $i
}

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(40)
$tasks = New-Object 'System.Collections.Generic.List[System.Threading.Tasks.Task]'
$meta = @()
for ($i = 0; $i -lt 12; $i++) {
    $ci = (Get-Date).Date.AddDays(100 + $i * 2)
    $co = $ci.AddDays(2)
    # ★ 这里必须带 userId，虽然服务端早就不读它了（P4.1 起身份从 token 取）——
    #   因为 CreateBookingDTO 上的 @NotNull 还在，不传会被 @Valid 拦成 400。
    #   「被逻辑忽略的字段却仍是必填」是个 API 层面的不一致，已记录待修。
    $body = @{ userId = 9001; hotelId = 1001; roomTypeId = 2001; checkIn = $ci.ToString('yyyy-MM-dd')
               checkOut = $co.ToString('yyyy-MM-dd'); roomCount = 1; guestCount = 1 } | ConvertTo-Json -Compress
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$($ports[$i])/booking")
    $req.Content = New-Object System.Net.Http.StringContent($body, [Text.Encoding]::UTF8, 'application/json')
    $req.Headers.Add('Idempotency-Key', [guid]::NewGuid().ToString('N'))
    $req.Headers.Add('Authorization', "Bearer $script:Token")
    $meta += [pscustomobject]@{ Port = $ports[$i]; CheckIn = $ci.ToString('yyyy-MM-dd') }
    $tasks.Add($client.SendAsync($req))
}

$ids = @()
$failedCount = 0
for ($i = 0; $i -lt $tasks.Count; $i++) {
    $resp = $tasks[$i].Result
    $txt = $resp.Content.ReadAsStringAsync().Result
    if ([int]$resp.StatusCode -eq 200) {
        $j = $txt | ConvertFrom-Json
        if ($j.data) { $ids += "$($j.data)" }
    } else {
        $failedCount++
        "    HTTP $([int]$resp.StatusCode)  $txt"
    }
}
"  12 个并发请求（8080/8081 各 6 个）：成功 $($ids.Count) 个，失败 $fail 个"
$uniq = @($ids | Select-Object -Unique)
"  返回的 bookingId：$($ids.Count) 个，去重后 $($uniq.Count) 个"

Check 'all concurrent bookings succeeded' ($failedCount -eq 0) "failed=$failedCount"
Check 'no duplicate bookingId returned' ($ids.Count -eq $uniq.Count) "total=$($ids.Count) unique=$($uniq.Count)"

$dupInDb = Sql @"
SELECT COUNT(*) FROM (SELECT booking_id FROM booking GROUP BY booking_id HAVING COUNT(*) > 1) t
"@
"  DB 里重复的 booking_id 行数 = $dupInDb"
Check 'no duplicate booking_id in DB' ("$dupInDb" -eq '0') "dup=$dupInDb"

# 雪花 ID 的时间位也检查一下：如果两个实例时钟/workerId 有问题，ID 会出现异常接近的重复段
$minId = ($ids | ForEach-Object { [decimal]$_ } | Measure-Object -Minimum).Minimum
$maxId = ($ids | ForEach-Object { [decimal]$_ } | Measure-Object -Maximum).Maximum
"  bookingId 范围：$minId ~ $maxId"
Check 'bookingIds look like snowflake ids (18~19 digits)' (@($ids | Where-Object { "$_".Length -lt 18 }).Count -eq 0) ''

# ---------------------------------------------------------------------------
"`n=== 2. ★ 超时关单：会不会被两个实例各释放一次 ==="
# 拿第一笔单，把 create_time 往前拨 20 分钟，让两个实例的超时任务都认为它该释放了
$target = $ids[0]
$targetCi = (Sql "SELECT check_in FROM booking WHERE booking_id=$target")
"  目标订单 = $target   check_in = $targetCi"
"  关单前：status=$(Sql "SELECT booking_status FROM booking WHERE booking_id=$target")  sold=$(DSold 2001 $targetCi)"

Sql "UPDATE booking SET create_time = DATE_SUB(NOW(), INTERVAL 20 MINUTE) WHERE booking_id=$target" | Out-Null
"  已把 create_time 回拨 20 分钟"

# ★ 关键：超时关单的【主路径】是从 Redis 延迟队列里认领，回拨 DB 的 create_time
#   并不会把它塞进队列；而扫表兜底默认 5 分钟一次（sweep-interval-ms=300000），
#   等它既慢又不可控。
#   所以这里【并发】调用两个实例的「立即扫表」接口 —— 正好制造了
#   "两个实例同时去释放同一笔超时单" 的竞争，这才是本节要验的东西。
$sw = New-Object System.Net.Http.HttpClient
$sw.Timeout = [TimeSpan]::FromSeconds(30)
# 扫表接口要管理员权限，所以直连时必须自己带 Authorization 头
$admTok = $script:AdminToken
$reqs = @()
foreach ($port in @($P1, $P2)) {
    $rq = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$port/booking/delay/sweep")
    $rq.Content = New-Object System.Net.Http.StringContent('')
    $rq.Headers.Add('Authorization', "Bearer $admTok")
    $reqs += $rq
}
$rs = @($reqs | ForEach-Object { $sw.SendAsync($_) })
$codes = @($rs | ForEach-Object { [int]$_.Result.StatusCode })
"  已并发触发两个实例的扫表：HTTP $($codes -join ' / ')"
Start-Sleep -Seconds 3

$st = Sql "SELECT booking_status FROM booking WHERE booking_id=$target"
$sold = DSold 2001 $targetCi
$role = Sql "SELECT cancel_role FROM booking WHERE booking_id=$target"
$ledgerReleased = Sql "SELECT IFNULL(SUM(released),0) FROM booking_inventory_link WHERE booking_id=$target"
$ledgerRows = Sql "SELECT COUNT(*) FROM booking_inventory_link WHERE booking_id=$target"
"  关单后：status=$st  cancel_role=$role  sold=$sold"
"  台账：$ledgerRows 行，其中 released=1 的行数合计 $ledgerReleased"

Check 'the order was expired (91)' ("$st" -eq '91') "actual=$st"
Check 'cancel_role is SYSTEM (3)' ("$role" -eq '3') "actual=$role"
Check 'inventory released exactly once (sold back to 0)' ("$sold" -eq '0') "actual=$sold"
Check 'ledger marks every row released (not partially)' ("$ledgerReleased" -eq "$ledgerRows") "released=$ledgerReleased rows=$ledgerRows"

# ---------------------------------------------------------------------------
"`n=== 3. 会话共享：8080 拿的 token，8081 认不认 ==="
$me1 = $null
try {
    $me1 = (Invoke-WebRequest -Method GET -Uri "$P2/auth/me" -Headers @{ Authorization = "Bearer $script:Token" } -UseBasicParsing -TimeoutSec 15).StatusCode
} catch { $me1 = [int]$_.Exception.Response.StatusCode }
"  用 8080 的 token 请求 8081/auth/me -> HTTP $me1"
Check 'token issued by one instance is accepted by the other' ($me1 -eq 200) "actual=$me1"

"`n=== 4. 最终一致性 ==="
$drift = Sql @"
SELECT COUNT(*) FROM room_inventory i
LEFT JOIN (SELECT room_type_id, stay_date, SUM(room_count) n FROM booking_inventory_link WHERE released=0 GROUP BY room_type_id, stay_date) l
  ON l.room_type_id=i.room_type_id AND l.stay_date=i.stay_date
WHERE i.sold_rooms <> IFNULL(l.n,0)
"@
Check 'no drift between ledger and inventory' ("$drift" -eq '0') "mismatched=$drift"

Summarize
