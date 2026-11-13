# =============================================================================
#  p4_kb_eval.ps1 —— 检索质量评测（recall@k）
#
#  为什么单独做：Agent 评测集衡量的是"意图和槽位对不对"，
#  但 RAG 里还有一环没人管 —— **检索到底有没有把正确的小节捞回来**。
#  检索错了，后面模型再聪明也只能基于错的资料作答。
#
#  做法：15 条「问题 -> 正确小节」的标注，逐条查 top3，
#  看正确小节有没有出现在第 1 位（recall@1）或前 3 位（recall@3）。
#
#  ★ 和端到端评测的区别：这里不依赖大模型（只打检索接口），
#    所以结果是确定的、可重复的、不花钱的 —— 可以当 CI 门槛。
# =============================================================================
param([double]$MinRecall3 = 0)

. "$PSScriptRoot\_testlib.ps1"

$casesPath = Join-Path $PSScriptRoot 'eval-kb-cases.json'

"=== 检索质量评测（recall@k）==="
"  今天 = $((Get-Date).ToString('yyyy-MM-dd HH:mm'))"

# 知识库接口要管理员
$adm = LoginAdmin
$cases = @(((Get-Content -Path $casesPath -Raw -Encoding UTF8) | ConvertFrom-Json))

"  用例数 = $($cases.Count)"
""

$hit1 = 0
$hit3 = 0
$misses = @()

foreach ($c in $cases) {
    $q = [uri]::EscapeDataString("$($c.q)")
    $r = Api GET "/booking/hotel/kb/search?q=$q&hotelId=$($c.hotelId)&size=3" ''
    if ($r.Http -ne 200 -or -not $r.Body.data) {
        "  [ERR ] $($c.id)  HTTP $($r.Http)"
        $misses += "$($c.id): HTTP $($r.Http)"
        continue
    }
    $hits = @($r.Body.data.hits)
    $sections = @($hits | ForEach-Object { "$($_.section)" })
    $rank = -1
    for ($i = 0; $i -lt $sections.Count; $i++) {
        if ($sections[$i] -eq "$($c.section)") { $rank = $i + 1; break }
    }
    if ($rank -eq 1) { $hit1++ }
    if ($rank -ge 1) { $hit3++ }

    if ($rank -ge 1) {
        "  [OK  ] {0,-14} 期望[{1}] 命中第 {2} 位   实际=[{3}]" -f $c.id, $c.section, $rank, ($sections -join ', ')
    } else {
        "  [MISS] {0,-14} 期望[{1}]   实际=[{2}]" -f $c.id, $c.section, ($sections -join ', ')
        $misses += "$($c.id): 期望[$($c.section)] 实际[$($sections -join ', ')]"
    }
}

$r1 = if ($cases.Count -gt 0) { 1.0 * $hit1 / $cases.Count } else { 0 }
$r3 = if ($cases.Count -gt 0) { 1.0 * $hit3 / $cases.Count } else { 0 }

""
"=== 结果 ==="
"  recall@1 = $hit1/$($cases.Count) = $('{0:P1}' -f $r1)"
"  recall@3 = $hit3/$($cases.Count) = $('{0:P1}' -f $r3)"
if ($misses.Count -gt 0) {
    "  （做一遍 agent 评测时注意：检索错的用例，模型回答也会错 —— 责任在检索，不在模型）"
}

if ($MinRecall3 -gt 0 -and $r3 -lt $MinRecall3) {
    "  [FAIL] recall@3 低于阈值 $('{0:P1}' -f $MinRecall3)"
    exit 1
}
exit 0
