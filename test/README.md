# 测试脚本

端到端测试（不是 JUnit 单元测试）。全部用 PowerShell 写，直接打 HTTP 接口 + 查 MySQL/Redis 做交叉验证。

## 为什么不用 JUnit

因为这些测试要验证的东西**跨了三层存储**：

> 「Redis 预占、`room_inventory.sold_rooms`、`booking_inventory_link` 台账」三者是否一致

单靠 `@SpringBootTest` + MockMvc 只能看到接口返回，看不到 Redis 和 DB 的真实状态。用脚本直连三种存储来对账，反而更直接。

## 前置条件

| 项 | 要求 |
|---|---|
| 应用 | 已在 `http://127.0.0.1:8080` 跑起来（`mvn spring-boot:run`） |
| MySQL | 通过 SSH 隧道可达（默认本地 `13306`），库 `hotel_booking` 已建好并灌过 `db/schema.sql` + `db/seed-p0.sql` |
| Redis | 通过 SSH 隧道可达（默认本地 `16379`） |
| 客户端工具 | 本地要有 `mysql.exe` 和 `redis-cli.exe` |

## 跑法

```powershell
# 每个套件都会自己重置测试数据，可以单独跑，也可以连着跑
powershell -NoProfile -ExecutionPolicy Bypass -File .\p0_flow_test.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\p0_1_reschedule_test.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\p1_inventory_cache_test.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\p1_idempotency_test.ps1
```

## 四个套件

| 文件 | 覆盖 |
|---|---|
| `p0_flow_test.ps1` | 下单 / 支付 / 取消 / 重复支付 / 重复取消 / 超卖 / 事务回滚 / 超时失效 / 90-91 分离 |
| `p0_1_reschedule_test.ps1` | 改期：延长 / 平移重叠 / 缩短 / 错开 / 改回（台账 upsert）/ 失败回滚 / 状态校验 |
| `p1_inventory_cache_test.ps1` | Redis 缓存：重建 / 双写 / 改期差集 / **快路径拦截** / **回滚补偿** / 冷缓存回源 / 跨月 / 对账 |
| `p1_idempotency_test.ps1` | 幂等：连点两次只下一单 / 参数变化算新请求 / 客户端 key 优先 / **Redis 清空后靠 DB 兜底回放** / 失败可重试 |
| `p2_delay_queue_test.ps1` | 延迟队列：入队出队时机 / 回滚不入队 / **到点自动释放** / **重复触发幂等** / **兜底扫表捞回** / 队列重建 / `volatile-lru` 保护 |
| `p2_geo_test.ps1` | 酒店 GEO：索引为空可辨识 / 重建 / 半径过滤 / **距离用独立 haversine 验算** / 排序 / limit / 参数校验 / 索引丢失可恢复 |
| `p2_nearby_filter_test.ps1` | 附近酒店：三种排序 / **排序与截断的顺序** / 有房过滤 / 过滤+排序叠加 / roomCount 两条路径 / 半个日期区间报错 |
| `p2_cleanup_test.ps1` | 定期清理：**过期**幂等记录被删而**未过期**的不许动 / 延迟队列残留（已终态、孤儿、非法成员）被清而有效任务保留 / 清理幂等 |
| `p3_absent_cache_test.ps1` | 防穿透：查不存在的订单/房型留标记 / **标记期间不再查库**（相对计数证明省了 10 次）/ 真实数据不受影响 / 标记删掉后自愈 / 每个标记都有 TTL |
| `p3_single_flight_test.ps1` | 单飞回源：**10 个并发只查 1 次库** / 不超卖（200×3 409×7）/ **无 5xx（无死锁）** / 锁不泄漏 / 别人占锁时仍能服务且不误删别人的锁 |
| `p3_ttl_jitter_test.ps1` | TTL 抖动：同一时刻建的一批 key **TTL 各不相同**（极差几千秒）/ 都落在 [基础值, 基础值+抖动] / 重建会重新随机 / 抖动不影响缓存命中 / 没有永久的键 |
| `p3_price_cache_test.ps1` | 价格缓存：第二次报价不查库 / **改价后报价立刻变（失效生效）** / 只失效那一天 / 改价真的落库 / 无价格日期 409 且记占位符不再查库 / 收尾还原种子价 |
| `p4_auth_test.ps1` | 鉴权：登录 / **密码错与用户不存在提示一致（防枚举）** / 无 token 与伪造 token 都 401 / **下单的 userId 来自 token、请求体里的被无视** / 退出后立刻失效 / 两个账号身份独立 |
| `p4_authz_test.ps1` | 越权：A 的单 B 查/付/取/改全部 **403** / B 看 A 的列表也 403 / **越权被拦下时状态与库存零副作用** / A 自己一切正常 / 不存在的单 404（先查存在再验归属） |
| `p4_agent_test.ps1` | Agent：一句话直达候选 / **多轮补槽位**（缺什么问什么）/ 床型过滤 / 订不到的日期 NO_RESULT / 过去日期打回追问 / 会话隔离 / 未登录 401 |

| `p4_agent_hitl_test.ps1` | 确认下单：草案进会话 / **确认后真的下单且数据正确** / **再确认不会下第二单** / 「算了」不下单 / 无草案时拒绝 / **下单内容以草案为准（用户话里提别的房型也没用）** / 草案过期不敢下单 |

合计 **14 个稳定套件 301 项 + 2 个 Agent 套件 53 项**。

> ## 两个 Agent 套件是「演示套件」，不作为回归门槛
>
> `p4_agent_test` / `p4_agent_hitl_test` 依赖**外部大模型**（DeepSeek）。
> 它们的定位是<b>证明这个项目接入了 Agent 能力</b>（意图理解、工具调用、多轮、人工确认），
> 而不是压并发 / 扛回归。
>
> 所以：
> - **14 个稳定套件（301 项）是回归门槛**，必须全绿再提交
> - 两个 Agent 套件**单独跑**验证功能即可；连续跑全量时它们偶发 502（外部 API 抖动/限流），
>   这是外部依赖的问题，不修
> - 没配 `DEEPSEEK_API_KEY` 时它们会打印 `[SKIP]` 退出 0没设 `DEEPSEEK_API_KEY` 时它会打印
> `[SKIP]` 并退出 0，不会让整个回归变红。它只断言**结构与语义**（stage / intent / 槽位 /
> 候选非空），不比对模型措辞 —— 措辞每次都可能不一样，钉死只会让测试变脆。

## 登录（P4 起所有业务接口都要 token）

种子里有**两个账号，密码都是 `123456`**：

| userId | username | 用途 |
|---|---|---|
| 9001 | `testuser` | 主测试账号 |
| 9002 | `otheruser` | 验证越权（A 不能看 B 的订单） |

**测试脚本不用手动登录** —— `_testlib.ps1` 里：

- `Api` 会自动把 `$script:Token` 放进 `Authorization: Bearer` 头
- `ResetTestData` 在清完 `hotel:*`（**顺带把 token 也清了**）之后会**自动重新登录**
- 想测"未登录被拦"就用 `ClearToken`，想换账号就用 `Login 'otheruser' '123456'`

> ⚠️ 如果自己写了不走 `Api` 的请求（比如用 `HttpClient` 做并发测试），**要手动加 token 头** ——
> `p3_single_flight_test` 就这么踩过一次，10 个并发全部 401。

## 环境变量（换机器时用，不必改脚本）

| 变量 | 默认值 |
|---|---|
| `HOTEL_API_BASE` | `http://127.0.0.1:8080` |
| `HOTEL_MYSQL_CLI` | `D:\MySQL\bin\mysql.exe` |
| `HOTEL_REDIS_CLI` | `F:\redis\Redis-x64-3.0.504\redis-cli.exe` |
| `HOTEL_DB_PORT` | `13306` |
| `HOTEL_REDIS_PORT` | `16379` |
| `HOTEL_DB_PWD` | `dzkjdx` |
| `HOTEL_REDIS_PWD` | `dzkjdx` |

## 两个必须知道的坑

### 1. 测试日期全部相对今天计算

`_testlib.ps1` 把日期锚定到**下一个周一**（`D(n)`），不是写死某一天。原因有两个：

- 写死的日期过两周就变成过去，`validateRange` 直接 400，整个套件失效
- 种子数据的价格是「工作日 400 / 周末 600」，锚到周一才能让金额断言（`1200`/`2000`/`+800`）保持固定值

需要**精确小时数**的场景（阶梯退款按 24/72 小时分档）用 `TD(n)` = 今天 + n 天。

### 2. `.ps1` 必须带 UTF-8 BOM

Windows PowerShell 5.1 读**无 BOM 的 UTF-8** 时会按系统 ANSI（GBK）解码，中文会被打碎成乱码字节，甚至吃掉引号导致语法错误。

而各种编辑器/工具保存时经常会把 BOM 去掉。**改完这些脚本一定要跑一次：**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\fix-bom.ps1
```

`fix-bom.ps1` 本身是纯 ASCII 的 —— 否则它会栽在自己要修的问题上。

## `ResetTestData` 清了什么

每个套件开头都会调用它：

```
MySQL : booking / room_inventory / booking_inventory_link / idempotent_record
Redis : hotel:*   （库存缓存 hotel:inv、幂等键 hotel:idem、延迟队列 hotel:delay…）
```

**Redis 里的状态也算测试状态。** 踩过两次：

1. 不清 `hotel:idem:*` → 第二轮用同样的请求体下单会命中上一轮的幂等记录，直接回放一个早已被取消的旧 `bookingId`，整个套件假失败
2. 不清 `hotel:delay:*` → 上一轮排着的超时任务会在本轮跑到一半时突然触发，把当前用例的订单提前释放掉

所以直接 `hotel:*` 全清，以后新增 key 前缀也不会再漏。

## 其它两个小陷阱

**`Set-StrictMode -Version 1.0`** 已开在 `_testlib.ps1` 里。PowerShell 默认把未声明变量当 `$null` —— 拼错一个变量名（比如写了 `$f22` 却从没声明）会静默传空串下去，最后报的是服务端 400，离真凶很远。开了 strict mode 就会直接指着那一行报错。

**变量名不要撞公共库。** PowerShell 的变量名**不区分大小写**，脚本里写个 `$fail = ...` 或 `$base = ...` 会直接覆盖掉库里的同名变量：

| 撞了谁 | 症状 |
|---|---|
| `$fail`（断言失败计数器） | `$script:fail++` 报 `operand is a PSObject`，计数乱掉 |
| `$base`（API 地址 `$script:Base`） | **所有请求变成 `HTTP -1`**，报错只说"连不上"，完全看不出真凶 |

库里占用的名字（测试脚本里别用）：

```
$Base  $Mysql  $Margs  $Rcli  $DbPort  $RedisPort  $DbPwd  $RedisPwd  $M0  $pass  $fail
```

`Api` 里加了运行时守卫：一旦发现 `$script:Base` 不再以 `http://` 开头，就直接抛出指名道姓的错误，而不是让你去猜为什么全部请求都失败。

**故意破坏状态之后要修回来。** 有些用例要故意把数据搞脏（比如把 Redis 里某天的库存改成"满"，验证快路径真的在拦请求）。这类用例**用完必须立刻从 DB 修回来**，否则后面的用例一旦用到同一天就会被假数据挡住。

> 踩过：`p1_inventory_cache_test` 的 §5 故意污染了"锚点 +21 天"那天的缓存且没修，当时跨月测试用的是另一天，相安无事；换了个日期后两者**撞到同一天**，跨月下单直接被假数据挡成 409，看起来像功能坏了。

## 语法预检

改完脚本先跑这个（不需要应用在跑）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\check-syntax.ps1
```

它查三件事：

1. **缺 BOM** —— PS 5.1 按 GBK 读无 BOM 的 UTF-8，中文被打碎后常变出幽灵引号
2. **语法错误** —— 字符串里混进一对 ASCII 双引号（`"这样"`）会把整行截断
3. **双引号不成对** —— 这类错误**语法可能仍然合法**（`"a" - "b"` 会被当成减法），报错要到运行时才出现（`Cannot convert value ... to type "System.Int32"`），前两项都抓不到，但引号不成对一定抓得到

前两种错误**长得一模一样**（都报 `Unexpected token`），肉眼很难分辨。所以检查器把 BOM 缺失单独指出来，别让它伪装成语法错误。

> 中文里请用「」或单引号，不要用 ASCII 的成对双引号 —— 这一条踩过 5 次。
