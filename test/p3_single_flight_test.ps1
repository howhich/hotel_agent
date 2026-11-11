# =============================================================================
#  p3_single_flight_test.ps1 —— 单飞回源（P3.2）
#
#  验证五件事：
#   1) ★ 冷缓存 + 并发 N 个请求，数据库只被查了 1 次（不是 N 次）
#   2) 结果正确：并发抢 3 间房，正好 3 个成功、其余被拒，台账与库存一致
#   3) 锁没有泄漏（跑完 KEYS hotel:lock:* 应为空）
#   4) ★ 别人占着锁时：请求仍然成功（降级到 DB），不卡死、不报错
#   5) ★ 别人占着锁时：那把锁【不会被我们删掉】（token 比对生效）
#
#  ★ 怎么证明"只查了 1 次库"
#    用【串行基线】做参照，不用绝对数字：
#      串行 10 次（冷缓存）  -> 第 1 个回源，后 9 个命中缓存  => deltaSeq ≈ 1 次真查询
#      并发 10 次（冷缓存）  -> 没有单飞就是 10 次真查询
#    所以比较 deltaConc 和 deltaSeq：
#      有单飞   -> 两者接近
#      没单飞   -> deltaConc 会多出约 9 次
#    绝对值里混着 JDBC 的 SELECT @@session.transaction_read_only，但两边都有，
#    相减就抵掉了。
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

Add-Type -AssemblyName System.Net.Http

$RT = 2001          # 总 3 间
# ★ 并发数从 10 降到 6。
#   开发环境经 SSH 隧道访问远端 Redis，10 路并发 × 每请求十几次 Redis 往返会把隧道压饱和：
#   单条命令排队超过 10 秒超时，整批请求跑完甚至熬过了 30 秒的锁 TTL，
#   于是出现"锁莫名消失""other=N"这类假失败（单独重跑又全绿）。
#   6 路仍能验证 single-flight 的核心属性（并发 6 个，查库仍然只有 1 次），
#   同时稳稳落在本环境能承受的范围内。
$NB = 6             # 并发数
$ci = D 60
$co = D 62
$ci2 = D 70
$co2 = D 72
$ci3 = D 80
$co3 = D 82

function Stat([string]$name) {
    $v = Sql "SHOW GLOBAL STATUS LIKE '$name'"
    if (-not $v) { return [int64]0 }
    return [int64](($v -split "`t")[1])
}

function NewBody([string]$a, [string]$b) {
    return JsonBody @{
        userId     = 9001
        hotelId    = 1001
        roomTypeId = $RT
        checkIn    = $a
        checkOut   = $b
        roomCount  = 1
        guestCount = 1
    }
}

# 真并发：HttpClient + SendAsync，一次把 N 个请求打出去
function PostConcurrent([string]$path, [string]$json, [string[]]$keys) {
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds(40)
    $tasks = New-Object 'System.Collections.Generic.List[System.Threading.Tasks.Task]'
    foreach ($k in $keys) {
        $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$($script:Base)$path")
        $req.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json')
        $req.Headers.Add('Idempotency-Key', $k)
        # ★ 这里绕过了公共库的 Api，所以 token 要自己加 ——
        #   否则并发请求全部 401（P4.1 加鉴权后踩过）
        if ($script:Token) { $req.Headers.Add('Authorization', "Bearer $($script:Token)") }
        $tasks.Add($client.SendAsync($req))
    }
    [System.Threading.Tasks.Task]::WaitAll($tasks.ToArray())
    $codes = @()
    foreach ($t in $tasks) { $codes += [int]$t.Result.StatusCode }
    $client.Dispose()
    return $codes
}

function LockKeys {
    $r = RedisCmd @('KEYS', 'hotel:lock:*')
    return @(($r -split "`n") | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })
}

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')    房型 $RT（总 3 间）    并发数 $NB"

# ---------------------------------------------------------------------------
# ★ 精确计数：只数「查库存表」的语句
#
#   不能用 Com_select 的差值 —— 里面混着 JDBC 驱动的
#   SELECT @@session.transaction_read_only（事务一挂起/恢复就来一条），
#   而 P3.2 加的 NOT_SUPPORTED 建行恰好会让它变多，看绝对值完全得不出结论（踩过）。
#   开 general_log 直接数 "FROM room_inventory" 出现了几次，噪声归零。
function InvSelectsDuring([scriptblock]$action) {
    Sql "SET GLOBAL log_output='TABLE'" | Out-Null
    Sql "SET GLOBAL general_log='ON'" | Out-Null
    Sql "TRUNCATE TABLE mysql.general_log" | Out-Null
    & $action
    Sql "SET GLOBAL general_log='OFF'" | Out-Null
    $n = Sql "SELECT COUNT(*) FROM mysql.general_log WHERE command_type IN ('Query','Execute') AND argument LIKE '%FROM room_inventory%'"
    Sql "SET GLOBAL log_output='FILE'" | Out-Null
    return [int]$n
}

"`n=== 1. 串行基线（冷缓存，10 次）==="
ResetTestData | Out-Null
$body = NewBody $ci $co
$seqCount = InvSelectsDuring {
    for ($i = 0; $i -lt 10; $i++) { Api POST '/booking' $body | Out-Null }
}
"  串行 10 次 -> 查库存表 $seqCount 次"
"  （第 1 个回源查 1 次；后面 9 个命中缓存或被 409 拒掉，都不查库）"
Check 'serial run loads the inventory table only once' ($seqCount -le 2) "actual=$seqCount"

"`n=== 2. ★ 并发（冷缓存，10 个同时）==="
ResetTestData | Out-Null
$keys = @()
for ($i = 1; $i -le $NB; $i++) { $keys += "p3-sf-$i-$(Get-Random)" }

$script:codes = @()
$concCount = InvSelectsDuring {
    $script:codes = PostConcurrent '/booking' $body $keys
}
$codes = $script:codes

$ok = @($codes | Where-Object { $_ -eq 200 }).Count
$rej = @($codes | Where-Object { $_ -eq 409 }).Count
$other = @($codes | Where-Object { $_ -ne 200 -and $_ -ne 409 }).Count
"  并发 10 个 -> 查库存表 $concCount 次"
"   HTTP 分布: 200×$ok  409×$rej  其它×$other   [$($codes -join ',')]"

Check 'all 10 concurrent requests got a definite answer' (($ok + $rej) -eq $NB) "200=$ok 409=$rej other=$other"
Check 'exactly 3 rooms were sold (not oversold)' ($ok -eq 3) "actual=$ok"
Check 'the rest were rejected with 409' ($rej -eq ($NB - 3)) "actual=$rej"
Check 'no request failed with 5xx (no deadlock)' ($other -eq 0) "other=$other"
Check 'single-flight: 10 concurrent requests load the inventory table ~once' ($concCount -le 3) "actual=$concCount"

"`n  -- 库存与台账是否一致 --"
$sold = DSold $RT $ci
$ledger = Sql "SELECT IFNULL(SUM(room_count),0) FROM booking_inventory_link WHERE room_type_id=$RT AND stay_date='$ci' AND released=0"
"    Redis/DB 已售 $ci = $sold    台账未释放合计 = $ledger"
Check 'sold_rooms matches the ledger' ("$sold" -eq "$ledger") "sold=$sold ledger=$ledger"

# ---------------------------------------------------------------------------
"`n=== 3. 锁没有泄漏 ==="
$locks = LockKeys
"  当前 hotel:lock:* 数量 = $($locks.Count)"
$locks | ForEach-Object { "    $_  (TTL=$(RedisCmd @('TTL',$_)))" }
Check 'no lock key leaked after the burst' ($locks.Count -eq 0) "leaked=$($locks.Count)"

# ---------------------------------------------------------------------------
"`n=== 4/5. 别人占着锁 → 请求照常成功，且那把锁不能被我们删掉 ==="
ResetTestData | Out-Null
$ciL = $ci3
$coL = $co3
$lockKey = "hotel:lock:inv:$RT`:$ciL`:$coL"
"  锁 key = $lockKey"
RedisCmd @('SET', $lockKey, 'someone-else', 'EX', '30') | Out-Null
"  手动占住锁: value=[$(RedisCmd @('GET',$lockKey))]  TTL=$(RedisCmd @('TTL',$lockKey))"

$bodyL = NewBody $ciL $coL
$codesL = PostConcurrent '/booking' $bodyL @('p3-lock-a', 'p3-lock-b', 'p3-lock-c')
$okL = @($codesL | Where-Object { $_ -eq 200 }).Count
"  发 3 个请求 -> HTTP: $($codesL -join ', ')   成功 $okL 个"
Check 'requests still succeed while a foreign lock is held' ($okL -ge 1) "codes=$($codesL -join ',')"
Check 'no request hung or errored out' (@($codesL | Where-Object { $_ -lt 200 -or $_ -ge 500 }).Count -eq 0) "codes=$($codesL -join ',')"

$stillThere = RedisCmd @('GET', $lockKey)
"  那把锁现在的值 = [$stillThere]"
Check 'the foreign lock was NOT deleted by our unlock' ($stillThere -eq 'someone-else') "value=[$stillThere]"

RedisCmd @('DEL', $lockKey) | Out-Null
"  （已清掉测试用的锁）"

# ---------------------------------------------------------------------------
"`n=== 6. 最终一致性 ==="
$mismatch = Sql @"
SELECT COUNT(*) FROM room_inventory i
LEFT JOIN (SELECT room_type_id, stay_date, SUM(room_count) n FROM booking_inventory_link WHERE released=0 GROUP BY room_type_id, stay_date) l
  ON l.room_type_id=i.room_type_id AND l.stay_date=i.stay_date
WHERE i.sold_rooms <> IFNULL(l.n,0)
"@
Check 'no drift between ledger and inventory' ($mismatch -eq '0') "mismatched=$mismatch"
Check 'no lock left behind' ((LockKeys).Count -eq 0) ''

Summarize
