# =============================================================================
#  p4_agent_eval.ps1 —— Agent 评测集（P2 之后的第一优先项）
#
#  为什么要有这个：
#    大模型的行为是**不确定**的。改一行提示词、加一个槽位，都可能让原来对的
#    说法变错 —— 而端到端测试只告诉你"这个流程还能走通"，不告诉你
#    "意图判断准不准"。
#
#    所以需要一层可量化的评测：一批固定的说法 + 期望的意图/槽位/阶段，
#    每次改完跑一遍，看三个数字：意图准确率 / 槽位准确率 / 端到端通过率。
#
#  怎么用：
#    powershell -NoProfile -ExecutionPolicy Bypass -File .\p4_agent_eval.ps1
#    powershell ... -File .\p4_agent_eval.ps1 -MinE2E 0.85   # 低于 85% 就以非 0 退出
#
#  ★ 每个用例一个【全新会话】：否则上一个用例的槽位会漏到下一个，测出来的不是模型的能力。
#  ★ 日期占位符：用例里写 @today / @+9，运行时按"今天"换算，
#    所以这份用例集不会因为时间推移而失效。
# =============================================================================
param(
    [double]$MinE2E = 0,
    # router = 确定性路由（默认）；tools = function calling。用于 A/B 对比
    [string]$Mode = 'router'
)

. "$PSScriptRoot\_testlib.ps1"

$casesPath = Join-Path $PSScriptRoot 'eval-cases.json'
$reportName = if ($Mode -eq 'router') { 'eval-report.md' } else { "eval-report-$Mode.md" }
$reportPath = Join-Path $PSScriptRoot $reportName

# ---------- 工具函数 ----------

# 把 @today / @+N 换成 yyyy-MM-dd
function ResolveDateToken([string]$v) {
    $today = (Get-Date).Date
    if ($v -eq '@today') { return $today.ToString('yyyy-MM-dd') }
    if ($v -match '^@\+(\d+)$') {
        return $today.AddDays([int]$matches[1]).ToString('yyyy-MM-dd')
    }
    if ($v -match '^@sat(?:\+(\d+))?$') {
        # 最近的周六（今天就是周六则取今天），可再加 N 天：@sat / @sat+1
        $add = (6 - [int]$today.DayOfWeek + 7) % 7
        if ($matches.Count -gt 1 -and $matches[1]) { $add += [int]$matches[1] }
        return $today.AddDays($add).ToString('yyyy-MM-dd')
    }
    return $v
}

# 安全读一个槽位（严格模式下访问不存在的属性会抛错）
function SlotOf($slots, [string]$key) {
    if ($null -eq $slots) { return '' }
    $p = $slots.PSObject.Properties[$key]
    if ($null -eq $p) { return '' }
    if ($null -eq $p.Value) { return '' }
    return "$($p.Value)".Trim()
}

function Pct([int]$a, [int]$b) {
    if ($b -eq 0) { return 'n/a' }
    return ('{0:N1}%' -f (100.0 * $a / $b))
}

# ---------- 准备 ----------

"=== Agent 评测（mode = $Mode）==="
"  今天 = $((Get-Date).ToString('yyyy-MM-dd HH:mm'))"

ResetTestData | Out-Null
"  测试数据已重置（评测从干净状态开始，日期都在价格日历范围内）"
""

if (-not (Test-Path $casesPath)) {
    throw "找不到用例文件：$casesPath"
}
$jsonText = Get-Content -Path $casesPath -Raw -Encoding UTF8
$parsed = $jsonText | ConvertFrom-Json
$cases = @($parsed)
"  用例数 = $($cases.Count)"
""

# ---------- 逐条跑 ----------

$rows = @()
$latencies = New-Object System.Collections.Generic.List[double]

$intentOk = 0
$stageOk = 0
$stageTotal = 0
$slotOk = 0
$slotTotal = 0
$e2eOk = 0
$errorCount = 0

$i = 0
foreach ($c in $cases) {
    $i++

    # 解析期望槽位（含日期占位符）
    $expected = @{}
    if ($c.slots) {
        foreach ($name in $c.slots.PSObject.Properties.Name) {
            $expected[$name] = ResolveDateToken "$($c.slots.$name)"
        }
    }

    # 把解析后的值替进消息里的 {key}
    $msg = "$($c.msg)"
    foreach ($k in $expected.Keys) {
        $msg = $msg.Replace("{$k}", $expected[$k])
    }

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $chatBody = @{ message = $msg }
    if ($Mode -ne 'router') { $chatBody['mode'] = $Mode }
    $r = Api POST '/agent/chat' (JsonBody $chatBody)
    $sw.Stop()
    $latencies.Add($sw.Elapsed.TotalSeconds)

    $gotIntent = ''
    $gotStage = ''
    $slots = $null
    if ($r.Http -eq 200 -and $r.Body.data) {
        $gotIntent = "$($r.Body.data.intent)"
        $gotStage = "$($r.Body.data.stage)"
        $slots = $r.Body.data.slots
    } else {
        $errorCount++
    }

    # 意图
    $okIntent = ($gotIntent -eq $c.intent)
    if ($okIntent) { $intentOk++ }

    # 阶段（用例里写 null 表示"这条不检查阶段"）
    $okStage = $true
    $expectStage = "$($c.stage)"
    if ($null -ne $c.stage -and $expectStage -ne '') {
        $stageTotal++
        $okStage = ($gotStage -eq $expectStage)
        if ($okStage) { $stageOk++ }
    }

    # 槽位：只看用例里声明了的字段
    $okSlots = $true
    $slotDiffs = @()
    foreach ($k in $expected.Keys) {
        $slotTotal++
        $want = "$($expected[$k])"
        $got = SlotOf $slots $k
        if ($got -eq $want) {
            $slotOk++
        } else {
            $okSlots = $false
            $slotDiffs += "${k}: 期望[$want] 实际[$got]"
        }
    }

    $okAll = $okIntent -and $okStage -and $okSlots
    if ($okAll) { $e2eOk++ }

    $mark = if ($okAll) { 'PASS' } else { 'FAIL' }
    $rows += [pscustomobject]@{
        Id        = $c.id
        Mark      = $mark
        Msg       = $msg
        ExpIntent = $c.intent
        GotIntent = $gotIntent
        ExpStage  = $expectStage
        GotStage  = $gotStage
        SlotDiffs = ($slotDiffs -join '; ')
        Http      = $r.Http
    }

    if ($okAll) {
        "  [{0,2}/{1}] PASS  {2}" -f $i, $cases.Count, $c.id
    } else {
        "  [{0,2}/{1}] FAIL  {2}   期望 intent={3} stage={4}  实际 intent={5} stage={6}" -f `
            $i, $cases.Count, $c.id, $c.intent, $expectStage, $gotIntent, $gotStage
        if ($slotDiffs.Count -gt 0) { "        槽位: $($slotDiffs -join '; ')" }
        if ($r.Http -ne 200) { "        HTTP $($r.Http)  $($r.Body.message)" }
    }
}

# ---------- 汇总 ----------

$sorted = @($latencies | Sort-Object)
function Percentile($arr, [double]$p) {
    if ($arr.Count -eq 0) { return 0 }
    $idx = [int][Math]::Min($arr.Count - 1, [Math]::Floor($arr.Count * $p))
    return $arr[$idx]
}
$p50 = Percentile $sorted 0.5
$p95 = Percentile $sorted 0.95

$e2eRate = if ($cases.Count -eq 0) { 0 } else { 1.0 * $e2eOk / $cases.Count }

""
"=== 结果 ==="
"  用例数          $($cases.Count)  （HTTP 失败 $errorCount 条）"
"  意图准确率      $intentOk/$($cases.Count) = $(Pct $intentOk $cases.Count)"
"  槽位字段准确率  $slotOk/$slotTotal = $(Pct $slotOk $slotTotal)"
"  阶段准确率      $stageOk/$stageTotal = $(Pct $stageOk $stageTotal)"
"  端到端通过      $e2eOk/$($cases.Count) = $(Pct $e2eOk $cases.Count)"
"  延迟            p50={0:N2}s  p95={1:N2}s" -f $p50, $p95

# ---------- 写报告 ----------

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('# Agent 评测报告（mode = ）')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('> 由 `test/p4_agent_eval.ps1` 自动生成，每次运行覆盖。')
[void]$sb.AppendLine('> 每个用例用一个**全新会话**，所以测的是模型的理解能力，不是上一轮的残留。')
[void]$sb.AppendLine('')
[void]$sb.AppendLine("- 运行时间：$((Get-Date).ToString('yyyy-MM-dd HH:mm'))")
[void]$sb.AppendLine("- 用例数：$($cases.Count)（其中 HTTP 失败 $errorCount 条）")
[void]$sb.AppendLine('')
[void]$sb.AppendLine('| 指标 | 结果 |')
[void]$sb.AppendLine('|---|---|')
[void]$sb.AppendLine("| 意图准确率 | $intentOk/$($cases.Count) = $(Pct $intentOk $cases.Count) |")
[void]$sb.AppendLine("| 槽位字段准确率 | $slotOk/$slotTotal = $(Pct $slotOk $slotTotal) |")
[void]$sb.AppendLine("| 阶段准确率 | $stageOk/$stageTotal = $(Pct $stageOk $stageTotal) |")
[void]$sb.AppendLine("| **端到端通过** | **$e2eOk/$($cases.Count) = $(Pct $e2eOk $cases.Count)** |")
[void]$sb.AppendLine(("| 延迟 | p50 = {0:N2}s，p95 = {1:N2}s |" -f $p50, $p95))
[void]$sb.AppendLine('')
$failRows = @($rows | Where-Object { $_.Mark -eq 'FAIL' })
if ($failRows.Count -eq 0) {
    [void]$sb.AppendLine('全部通过 ✅')
} else {
    [void]$sb.AppendLine("## 失败明细（$($failRows.Count) 条）")
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('| 用例 | 说法 | 期望 | 实际 | 槽位差异 |')
    [void]$sb.AppendLine('|---|---|---|---|---|')
    foreach ($row in $failRows) {
        $exp = "intent=$($row.ExpIntent)"
        if ($row.ExpStage -ne '') { $exp += " / stage=$($row.ExpStage)" }
        $got = "intent=$($row.GotIntent)"
        if ($row.GotStage -ne '') { $got += " / stage=$($row.GotStage)" }
        if ($row.Http -ne 200) { $got = "HTTP $($row.Http)" }
        $diffs = if ($row.SlotDiffs) { $row.SlotDiffs } else { '—' }
        [void]$sb.AppendLine("| ``$($row.Id)`` | $($row.Msg) | $exp | $got | $diffs |")
    }
}
[IO.File]::WriteAllText($reportPath, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
""
"  报告已写入: $reportPath"

if ($MinE2E -gt 0 -and $e2eRate -lt $MinE2E) {
    ""
    "  [FAIL] 端到端通过率 $('{0:P1}' -f $e2eRate) 低于阈值 $('{0:P1}' -f $MinE2E)"
    exit 1
}
exit 0
