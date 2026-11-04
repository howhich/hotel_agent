# =============================================================================
#  check-syntax.ps1 —— 语法预检（不需要应用在跑）
#
#  为什么需要它：
#    测试脚本里经常写中文注释和中文提示，如果字符串里混进一对 ASCII 双引号
#    （"这样"），整行就断了，PowerShell 报的却是一个莫名其妙的 Unexpected token，
#    而且行号指向的是那一行——不是因为内容错了，是因为引号把字符串截断了。
#    这个错误踩过好几次，肉眼扫很难发现（看起来完全正常）。
#
#  用法：
#    powershell -NoProfile -ExecutionPolicy Bypass -File .\check-syntax.ps1
#    有任何语法错误就退出码 1（可以挂到 CI 上）
# =============================================================================

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$dir = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
$self = 'check-syntax.ps1'

$failed = 0
$checked = 0

Get-ChildItem $dir -Filter *.ps1 -File | Sort-Object Name | ForEach-Object {
    if ($_.Name -eq $self) { return }

    $checked++

    # 先查 BOM。PS 5.1 读无 BOM 的 UTF-8 会按 GBK 解码，中文被打碎后经常变出
    # 幽灵的引号/花括号，报一堆「Unexpected token '}'」——根因完全看不出来。
    # 所以把 BOM 缺失单独指出来，别让它伪装成语法错误。
    # （任何编辑器/write/edit 工具保存时都可能把 BOM 去掉，改完就跑 fix-bom.ps1）
    $bytes = [IO.File]::ReadAllBytes($_.FullName)
    $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    $isAscii = $true
    foreach ($b in $bytes) { if ($b -gt 0x7F) { $isAscii = $false; break } }
    if (-not $hasBom -and -not $isAscii) {
        $failed++
        "  [FAIL] $($_.Name)  -- 缺 UTF-8 BOM（PS 5.1 会按 GBK 解析，中文会打碎成幽灵字符）"
        "         修法: powershell -File .\fix-bom.ps1"
        return
    }

    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile(
        $_.FullName, [ref]$tokens, [ref]$errors)

    if ($errors -and $errors.Count -gt 0) {
        $failed++
        "  [FAIL] $($_.Name)"
        foreach ($e in $errors) {
            "         行 $($e.Extent.StartLineNumber): $($e.Message)"
            "         源码: $($e.Extent.StartLineNumber) -> $((Get-Content $_.FullName -TotalCount $e.Extent.StartLineNumber)[-1].Trim())"
        }
    } else {
        "  [OK]   $($_.Name)"
    }
}

""
if ($failed -gt 0) {
    "  >>> $checked 个文件，$failed 个有语法错误"
    exit 1
} else {
    "  >>> $checked 个文件，全部通过"
}
