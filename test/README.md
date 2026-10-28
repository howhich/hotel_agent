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

合计 **123 项断言**。

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

**变量名不要叫 `$fail`**：公共库里 `$script:fail` 是断言失败计数器，脚本里再定义一个 `$fail` 会把它覆盖成 PSObject，然后 `$script:fail++` 就会报 "operand is a PSObject"。
