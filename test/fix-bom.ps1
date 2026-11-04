# =============================================================================
#  fix-bom.ps1 -- add UTF-8 BOM to the test scripts under devops\
#
#  WHY: Windows PowerShell 5.1 reads BOM-less UTF-8 using the system ANSI
#  code page (GBK on this machine). Chinese text then decodes into garbage
#  bytes, which can even swallow a quote and produce a syntax error.
#
#  IMPORTANT: the write/edit tools strip the BOM, so re-run this script after
#  every edit to any of these files.
#
#  This script itself is ASCII-only on purpose: if it contained Chinese it
#  would suffer from the very problem it fixes.
# =============================================================================
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$dir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$bom = New-Object Text.UTF8Encoding($true)   # $true = emit BOM

$files = @(
    '_testlib.ps1',
    'p0_flow_test.ps1',
    'p0_1_reschedule_test.ps1',
    'p1_inventory_cache_test.ps1',
    'p1_idempotency_test.ps1',
    'p2_delay_queue_test.ps1',
    'p2_geo_test.ps1',
    'p2_nearby_filter_test.ps1',
    'check-syntax.ps1'
)

foreach ($f in $files) {
    $p = Join-Path $dir $f
    if (-not (Test-Path $p)) {
        "  {0,-30} skipped (not found)" -f $f
        continue
    }
    $bytes = [IO.File]::ReadAllBytes($p)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        "  {0,-30} already has BOM" -f $f
        continue
    }
    $txt = [IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)
    [IO.File]::WriteAllText($p, $txt, $bom)
    "  {0,-30} BOM added" -f $f
}
