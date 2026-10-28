# =============================================================================
#  devops\_testlib.ps1 —— 测试脚本公共库
#
#  用法（测试脚本开头）:
#      . "$PSScriptRoot\_testlib.ps1"
#
#  三个设计要点:
#
#  1) 所有测试日期都【相对今天】计算。以前脚本里写死 2026-10-12 这种，
#     过两周就全变成"过去日期"，validateRange 直接 400，测试集体失效。
#
#  2) 日期锚定到【下一个周一】。种子数据的价格是"工作日 400 / 周末 600"，
#     锚到周一以后测试窗口全落在工作日，金额断言（1200 / 2000 / +800…）
#     才稳定、可读。注意这是原脚本的隐含假设（10/12 那天正好是周一）。
#
#  3) PowerShell 的命令解析优先级是【别名 > 函数】。辅助函数名一旦撞上别名
#     就会被静默替换掉，脚本照跑但结果全错（踩过两次：
#     R=Invoke-History、Compare=Compare-Object、Rd=Remove-Item）。
#     所以下面先做一次碰撞检查，撞了就立刻 throw。
# =============================================================================

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

# 让"引用了未声明的变量"立刻报错。
# PowerShell 默认把未定义变量当 $null —— 拼错一个变量名会静默传空串下去，
# 最后报的却是服务端 400，很难定位（踩过：$f22 从没声明，checkOut 变成空串）。
# Version 1.0 只查未初始化变量，不管"属性不存在"，才不会误伤 $r.Body.data 这类写法。
Set-StrictMode -Version 1.0

# ---------- 环境相关的东西都可以用环境变量覆盖 ----------
# 默认值对应本项目开发机（本地 MySQL/redis 客户端 + SSH 隧道端口）。
# 换台机器就设这几个环境变量，不用改脚本：
#   HOTEL_API_BASE   API 地址           默认 http://127.0.0.1:8080
#   HOTEL_MYSQL_CLI  mysql 客户端路径    默认 D:\MySQL\bin\mysql.exe
#   HOTEL_REDIS_CLI  redis-cli 路径      默认 F:\redis\Redis-x64-3.0.504\redis-cli.exe
#   HOTEL_DB_PORT    MySQL 本地端口      默认 13306（SSH 隧道）
#   HOTEL_REDIS_PORT Redis 本地端口      默认 16379（SSH 隧道）
#   HOTEL_DB_PWD     MySQL 密码          默认 dzkjdx
#   HOTEL_REDIS_PWD  Redis 密码          默认 dzkjdx
$script:Base      = if ($env:HOTEL_API_BASE)  { $env:HOTEL_API_BASE }  else { 'http://127.0.0.1:8080' }
$script:Mysql     = if ($env:HOTEL_MYSQL_CLI) { $env:HOTEL_MYSQL_CLI } else { 'D:\MySQL\bin\mysql.exe' }
$script:Rcli      = if ($env:HOTEL_REDIS_CLI) { $env:HOTEL_REDIS_CLI } else { 'F:\redis\Redis-x64-3.0.504\redis-cli.exe' }
$script:DbPort    = if ($env:HOTEL_DB_PORT)   { $env:HOTEL_DB_PORT }   else { '13306' }
$script:RedisPort = if ($env:HOTEL_REDIS_PORT){ $env:HOTEL_REDIS_PORT }else { '16379' }
$script:DbPwd     = if ($env:HOTEL_DB_PWD)    { $env:HOTEL_DB_PWD }    else { 'dzkjdx' }
$script:RedisPwd  = if ($env:HOTEL_REDIS_PWD) { $env:HOTEL_REDIS_PWD } else { 'dzkjdx' }

$env:MYSQL_PWD = $script:DbPwd
$script:Margs = @('-h','127.0.0.1','-P',$script:DbPort,'-u','root','--default-character-set=utf8mb4','-N','-B','hotel_booking')

# ---------- 别名碰撞守卫 ----------
foreach ($n in 'Api','Sql','RedisCmd','Check','Summarize','D','TD','DSold','RSold','RSet',
               'DKey','DiffCount','BCount','PriceSum','NewBooking','NewPaidBooking',
               'ResetTestData','JsonBody') {
    $c = Get-Command -Name $n -ErrorAction SilentlyContinue
    if ($c -and $c.CommandType -eq 'Alias') {
        throw "helper name '$n' collides with alias '$($c.Definition)'"
    }
}

# ---------- 日期锚定 ----------
$script:T0 = (Get-Date).Date
$dow = [int]$script:T0.DayOfWeek            # Sunday=0 ... Saturday=6
$daysToMon = (8 - $dow) % 7
if ($daysToMon -eq 0) { $daysToMon = 7 }    # 今天就是周一 -> 取下周一，留出缓冲
$script:M0 = $script:T0.AddDays($daysToMon)

# D  n = 锚点（下一个周一）+ n 天。测试窗口用它，保证落在工作日。
function D([int]$n)  { $script:M0.AddDays($n).ToString('yyyy-MM-dd') }
# TD n = 今天 + n 天。需要"精确小时数"的场景用它（比如阶梯退款按 24/72 小时分档）。
function TD([int]$n) { $script:T0.AddDays($n).ToString('yyyy-MM-dd') }

$script:pass = 0
$script:fail = 0

function Check([string]$name, [bool]$ok, [string]$detail) {
    if ($ok) { $script:pass++; "  [PASS] $name   $detail" }
    else     { $script:fail++; "  [FAIL] $name   $detail" }
}

function Summarize {
    "`n=== SUMMARY ==="
    "  PASS = $script:pass    FAIL = $script:fail"
}

# ---------- HTTP ----------
function Api {
    param([string]$Method, [string]$Path, [string]$Json, [hashtable]$Headers)
    $p = @{ Method=$Method; Uri=($script:Base+$Path); ContentType='application/json; charset=utf-8'; TimeoutSec=30 }
    if ($Json) { $p.Body = [Text.Encoding]::UTF8.GetBytes($Json) }
    if ($Headers) { $p.Headers = $Headers }
    try {
        $r = Invoke-WebRequest @p -UseBasicParsing
        # 不要用 $r.Content：响应头若没 charset，PS 5.1 会按 Latin-1 解码，中文变乱码。
        # 统一从原始字节显式按 UTF-8 解。
        $txt = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        return [pscustomobject]@{ Http=[int]$r.StatusCode; Body=($txt | ConvertFrom-Json); Headers=$r.Headers }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $sr = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
            $t = $sr.ReadToEnd()
            try { $j = $t | ConvertFrom-Json } catch { $j = $null }
            return [pscustomobject]@{ Http=[int]$resp.StatusCode; Body=$j; Headers=$resp.Headers }
        }
        return [pscustomobject]@{ Http=-1; Body=$null }
    }
}

# ---------- MySQL / Redis ----------
function Sql([string]$q)          { ((& $script:Mysql @script:Margs -e $q 2>$null) -join ' ').Trim() }
function RedisCmd([string[]]$c)   { ((& $script:Rcli -p $script:RedisPort -a $script:RedisPwd @c 2>$null) -join "`n").Trim() }

function DKey([int]$rt, [string]$date) { "hotel:inv:{rt:$rt}:" + $date.Substring(0,7) }

# DB 里的已售（没有库存行 = 0，因为库存行是下单时才惰性创建的）
function DSold([int]$rt, [string]$d) {
    Sql "SELECT IFNULL((SELECT sold_rooms FROM room_inventory WHERE room_type_id=$rt AND stay_date='$d'),0)"
}
# Redis 里的已售（没有这天返回 'MISS'）
function RSold([int]$rt, [string]$d) {
    $v = RedisCmd @('HGET', (DKey $rt $d), $d)
    if ($v -eq '') { return 'MISS' } else { return $v }
}
function RSet([int]$rt, [string]$d, [int]$v) { RedisCmd @('HSET', (DKey $rt $d), $d, "$v") | Out-Null }

function BCount { Sql "SELECT COUNT(*) FROM booking" }

function DiffCount([int]$rt, [string]$from, [string]$to) {
    $r = Api GET "/booking/inv/compare?roomTypeId=$rt&from=$from&to=$to" ''
    return $r.Body.data.diffCount
}

# 区间内的价格合计（[from, to) 左闭右开）—— 日期不锚定时用它算期望值
function PriceSum([int]$rt, [string]$from, [string]$to) {
    Sql "SELECT IFNULL(SUM(price),0) FROM room_price WHERE room_type_id=$rt AND stay_date >= '$from' AND stay_date < '$to'"
}

# ---------- 业务快捷方式 ----------
# 用 hashtable + ConvertTo-Json 构造请求体，而不是 '-f' 模板：
#   '-f' 里 JSON 的字面花括号必须写成 {{ }}，很容易漏（踩过）。
function JsonBody([hashtable]$h) { ($h | ConvertTo-Json -Compress -Depth 5) }

function NewBooking([int]$rt, [string]$ci, [string]$co, [int]$rooms) {
    $json = JsonBody @{
        userId     = 9001
        hotelId    = 1001
        roomTypeId = $rt
        checkIn    = $ci
        checkOut   = $co
        roomCount  = $rooms
        guestCount = 1
    }
    return Api POST '/booking' $json
}

function NewPaidBooking([int]$rt, [string]$ci, [string]$co) {
    $c = NewBooking $rt $ci $co 1
    if ($c.Http -ne 200) { return $c }          # 失败就把响应原样返回，调用方自己判
    $id = $c.Body.data
    Api POST "/booking/$id/pay" '{"payChannel":1}' | Out-Null
    return [pscustomobject]@{ Http=200; Body=[pscustomobject]@{ data=$id } }
}

# 把 DB 的测试数据清空，并删掉 Redis 里所有本项目的键
#
#  ★ Redis 里的状态也算"测试状态"。踩过两次：
#    1) 不清 hotel:idem:*，下一轮用同样的请求体下单会命中上一轮的幂等记录、
#       回放一个旧 bookingId，整个套件集体假失败
#    2) 不清 hotel:delay:*，上一轮排着的超时任务会在本轮跑到一半时突然触发，
#       把当前用例的订单提前释放掉
#    所以这里直接用 hotel:* 全清 —— 新增 key 前缀也不会再漏。
function ResetTestData {
    Sql "DELETE FROM booking_inventory_link; DELETE FROM room_inventory; DELETE FROM booking; DELETE FROM idempotent_record;" | Out-Null
    $n = 0
    foreach ($pat in @('hotel:*')) {
        $keys = RedisCmd @('KEYS', $pat)
        if ($keys) {
            foreach ($k in ($keys -split "`n")) {
                if ($k.Trim()) { RedisCmd @('DEL', $k.Trim()) | Out-Null; $n++ }
            }
        }
    }
    return $n
}
