# =============================================================================
#  p1_inventory_cache_test.ps1 —— Redis 区间库存缓存
#
#  重点验证三件事：
#   1) Redis 快路径真的在拦请求（不是摆设）
#   2) 事务回滚时 Redis 预占真的被补偿
#   3) 缓存被淘汰/清空时不影响正确性（自动回源）
# =============================================================================
. "$PSScriptRoot\_testlib.ps1"

$RT = 2001

$d0 = D 0;  $d1 = D 1;  $d2 = D 2;  $d3 = D 3
$s8 = D 8;  $s9 = D 9;  $s10 = D 10; $s11 = D 11; $s12 = D 12
$g21 = D 21; $g22 = D 22          # 快路径拦截
$m28 = D 28; $m29 = D 29          # 冷缓存回源
$r35 = D 35; $r36 = D 36          # 回滚补偿

# 跨月：取锚点所在月份的最后一天，再 +2 天
$firstOfMonth = Get-Date -Year $script:M0.Year -Month $script:M0.Month -Day 1
$cmIn  = $firstOfMonth.AddMonths(1).AddDays(-1)
$cmOut = $cmIn.AddDays(2)
$cmInS  = $cmIn.ToString('yyyy-MM-dd')
$cmOutS = $cmOut.ToString('yyyy-MM-dd')

"=== 环境 ==="
"  今天 = $(Get-Date -Format 'yyyy-MM-dd HH:mm')    锚点(周一) = $($script:M0.ToString('yyyy-MM-dd'))"
"  跨月窗口 = $cmInS ~ $cmOutS"

# ---------------------------------------------------------------------------
"`n=== 0. 重置（DB + Redis）==="
$nk = ResetTestData
"  DB bookings=$(BCount)   Redis 键清掉 $nk 个   dbsize=$(RedisCmd @('DBSIZE'))"

# ---------------------------------------------------------------------------
"`n=== 1. 从 DB 重建 Redis ==="
$rb = Api POST "/booking/inv/rebuild?roomTypeId=$RT&from=$d0&to=$(D 30)" ''
"  HTTP $($rb.Http)  writtenDays=$($rb.Body.data.writtenDays)"
Check 'rebuild wrote 30 days' ($rb.Body.data.writtenDays -eq 30) ''
Check 'Redis agrees with DB after rebuild' ((DiffCount $RT $d0 (D 30)) -eq 0) ''
"  样例: $d0=$(RSold $RT $d0)  $cmInS=$(RSold $RT $cmInS)   键=$(RedisCmd @('KEYS','hotel:inv:*') -replace "`n",' | ')"

# ---------------------------------------------------------------------------
"`n=== 2. 下单同时写 DB 和 Redis ==="
$c = NewBooking $RT $d0 $d3 1
$bid = $c.Body.data
"  book $d0~$d3 -> HTTP $($c.Http)  bookingId=$bid"
"    DB   : $d0=$(DSold $RT $d0)  $d1=$(DSold $RT $d1)  $d2=$(DSold $RT $d2)  $d3=$(DSold $RT $d3)"
"    Redis: $d0=$(RSold $RT $d0)  $d1=$(RSold $RT $d1)  $d2=$(RSold $RT $d2)  $d3=$(RSold $RT $d3)"
Check 'Redis sold=1 on the 3 stayed days' ((RSold $RT $d0) -eq '1' -and (RSold $RT $d1) -eq '1' -and (RSold $RT $d2) -eq '1') ''
Check 'Redis leaves checkout day alone (half-open)' ((RSold $RT $d3) -eq '0') ''
Check 'Redis == DB after booking' ((DiffCount $RT $d0 (D 30)) -eq 0) ''

"`n=== 3. 取消（Redis 释放在事务提交之后执行）==="
$cx = Api POST "/booking/$bid/cancel" '{"reason":"test","cancelRole":1}'
"  HTTP $($cx.Http)    DB $d0=$(DSold $RT $d0)   Redis $d0=$(RSold $RT $d0)"
Check 'Redis released back to 0' ((RSold $RT $d0) -eq '0' -and (RSold $RT $d1) -eq '0') ''
Check 'Redis == DB after cancel' ((DiffCount $RT $d0 (D 30)) -eq 0) ''

# ---------------------------------------------------------------------------
"`n=== 4. 改期在 Redis 侧也只动差集 ==="
$c2 = NewBooking $RT $s8 $s11 1
$bid2 = $c2.Body.data
"  book $s8~$s11 -> bookingId=$bid2"
$rs = Api POST "/booking/$bid2/reschedule" (JsonBody @{ newCheckIn=$s9; newCheckOut=$s12 })
"  改到 $s9~${s12}: released=[$($rs.Body.data.releasedDates -join ',')] occupied=[$($rs.Body.data.occupiedDates -join ',')]"
"    Redis: $s8=$(RSold $RT $s8)  $s9=$(RSold $RT $s9)  $s10=$(RSold $RT $s10)  $s11=$(RSold $RT $s11)"
Check 'reschedule: Redis released the head day' ((RSold $RT $s8) -eq '0') ''
Check 'reschedule: Redis holds the shifted days' ((RSold $RT $s9) -eq '1' -and (RSold $RT $s10) -eq '1' -and (RSold $RT $s11) -eq '1') ''
Check 'Redis == DB after reschedule' ((DiffCount $RT $d0 (D 30)) -eq 0) ''

# ---------------------------------------------------------------------------
"`n=== 5. *** Redis 快路径真的在拦请求 *** ==="
"  把 Redis 的 $g21 改成 3（满），而 DB 仍是 0。"
"  如果 Redis 不拦，请求会走到 DB —— DB 是 0+1<=3，会痛快放行并建单。"
RSet $RT $g21 3
"    before: Redis $g21=$(RSold $RT $g21)   DB $g21=$(DSold $RT $g21)   bookings=$(BCount)"
$before = BCount
$g = NewBooking $RT $g21 $g22 1
"    attempt -> HTTP $($g.Http)  msg=$($g.Body.message)"
"    after : Redis $g21=$(RSold $RT $g21)   DB $g21=$(DSold $RT $g21)   bookings=$(BCount)"
Check 'rejected with 409' ($g.Http -eq 409) ''
Check 'DB never touched (sold still 0)' ((DSold $RT $g21) -eq '0') ''
Check 'no booking row created' ((BCount) -eq $before) ''

# ★ 上面为了验证"快路径真的在拦"，故意把这个日期的缓存改成了满值。
#   用完必须立刻从 DB 修回来 —— 否则后面用到同一日期（比如跨月测试正好落在这天）
#   会被这条假数据挡住，表现为"功能坏了"。
Api POST "/booking/inv/rebuild?roomTypeId=$RT&from=$g21&to=$g22" '' | Out-Null
"    -- 已从 DB 修复 $g21 的缓存值: $(RSold $RT $g21) --"

# ---------------------------------------------------------------------------
"`n=== 6. 冷缓存自动回源（绝不让缓存问题影响用户）==="
"  删掉 $m28 所在月份的整个 bucket"
RedisCmd @('DEL', (DKey $RT $m28)) | Out-Null
"    Redis $m28 = $(RSold $RT $m28)  (MISS)"
$m = NewBooking $RT $m28 $m29 1
"    attempt -> HTTP $($m.Http)  bookingId=$($m.Body.data)"
"    after: Redis $m28=$(RSold $RT $m28)   DB $m28=$(DSold $RT $m28)"
Check 'booking succeeds despite cold cache' ($m.Http -eq 200) ''
Check 'Redis repopulated by the fallback' ((RSold $RT $m28) -eq '1') ''

# ---------------------------------------------------------------------------
"`n=== 7. *** 回滚补偿 Redis 预占 *** ==="
"  先用 3 间占满 DB 的 $r35 / $r36，再把 Redis 改成 0（比 DB 旧）"
$blk = NewBooking $RT $r35 $r36 3
"    blocker -> HTTP $($blk.Http)   DB $r35=$(DSold $RT $r35)  Redis $r35=$(RSold $RT $r35)"
RSet $RT $r35 0
RSet $RT $r36 0
"    现在 Redis 说 0/0，DB 说 3/3"
$f = NewBooking $RT $r35 $r36 1
"    attempt -> HTTP $($f.Http)  msg=$($f.Body.message)"
"    after: Redis $r35=$(RSold $RT $r35)  $r36=$(RSold $RT $r36)"
Check 'DB (the arbiter) rejects with 409' ($f.Http -eq 409) ''
Check 'Redis reservation was COMPENSATED (back to 0/0)' ((RSold $RT $r35) -eq '0' -and (RSold $RT $r36) -eq '0') 'compensation failure would leave 1/1'

"  我们故意制造的旧值与 DB 不一致，那就是真漂移 —— compare 必须能看出来"
"    diffCount = $(DiffCount $RT $r35 $r36)"
Check 'compare DETECTS the injected drift' ((DiffCount $RT $r35 $r36) -ge 1) ''
Api POST "/booking/inv/rebuild?roomTypeId=$RT&from=$r35&to=$r36" '' | Out-Null
"    rebuild 之后 diffCount = $(DiffCount $RT $r35 $r36)"
Check 'rebuild repairs the injected drift' ((DiffCount $RT $r35 $r36) -eq 0) ''

# ---------------------------------------------------------------------------
"`n=== 8. 跨月区间（两个 bucket，hash tag 保证同 slot）==="
$cm = NewBooking $RT $cmInS $cmOutS 1
"  book $cmInS~$cmOutS -> HTTP $($cm.Http)  bookingId=$($cm.Body.data)"
"    两个 bucket 键:"
RedisCmd @('KEYS','hotel:inv:{rt:2001}:*') -split "`n" | ForEach-Object { "      $_" }
Check 'cross-month booking succeeded' ($cm.Http -eq 200) ''
Check 'the month-boundary day is held in Redis' (((RSold $RT $cmInS) -eq (DSold $RT $cmInS) -and [int](DSold $RT $cmInS) -ge 1)) ''
Check 'no drift over the whole range' ((DiffCount $RT $d0 (D 60)) -eq 0) ''

# ---------------------------------------------------------------------------
"`n=== 9. 全区间对账 + 破坏后修复 ==="
"  compare($d0 .. $(D 60)) -> diffCount = $(DiffCount $RT $d0 (D 60))"
Check 'zero drift across the whole range' ((DiffCount $RT $d0 (D 60)) -eq 0) ''

RSet $RT $d0 99
"  手工把 Redis $d0 改成 99 -> diffCount = $(DiffCount $RT $d0 (D 3))"
Check 'compare DETECTS deliberate drift' ((DiffCount $RT $d0 (D 3)) -ge 1) ''
Api POST "/booking/inv/rebuild?roomTypeId=$RT&from=$d0&to=$(D 3)" '' | Out-Null
"  rebuild 之后 -> diffCount = $(DiffCount $RT $d0 (D 3))"
Check 'rebuild repairs it' ((DiffCount $RT $d0 (D 3)) -eq 0) ''

Summarize
