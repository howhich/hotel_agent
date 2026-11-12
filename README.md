# hotel-booking

**酒店 / 民宿预订后端**，重点解决两件真实项目里最容易做错的事：

- **区间库存**（房型 × 日期区间）在高并发下不超卖、不改错
- **接入 Agent**（说人话订房），但**不让模型决定订单内容**

---

## 为什么值得看

这个项目里有三个"看起来简单、做对了很难"的点：

| 表面 | 实际 |
|---|---|
| 订个房，扣库存 | 库存单元是「房型 × 日期区间」，一个订单要跨多天同时成功 |
| 加个缓存少查库 | 真正的难点不是"变快"，是让缓存和数据库**永远不打架** |
| 加个大模型 | 难点不是"调通 API"，是让它**帮用户订房的同时不能改单** |

业务闭环是完整的：

```
登录 → 查可订 → 报价 → 下单 → 支付 → 改期 → 取消
        ↑                          ↓
     Agent 也能走完整条：说话 → 查 → 出草案 → 说"确认" → 真的下单
```

---

## 核心难点：库存不能用计数器

打车系统里"一个城市有 N 辆车"是一个**数**。酒店不是。

酒店库存的天然形状是**按天**的：

```
房型 2001，总共 3 间
             11-19   11-20   11-21   11-22
sold_rooms     1       2       3       0      ← 每天一行，UNIQUE(room_type_id, stay_date)

11-20 住到 11-22 → 占的是 11-20 和 11-21 两天
                   11-22 那天是别人的
```

一个 2 晚的订单要**同时**在这 2 行上成功；任何一行不够就整单失败，**一行都不能留**。

这是本项目的全部复杂度来源 —— 也是"改期"、"退订"、"对账"全都比单值库存难一档的原因。

---

## 五个关键设计

### 1. 左闭右开 `[checkIn, checkOut)`

`11-20 到 11-22` 占 **2 天**（11-20、11-21），**不含离店日**。

库存、价格、台账、测试全部遵守这一条 —— 否则边界永远差一天，而且这种 bug 只在月份切换和改期时暴露。

### 2. 条件 UPDATE 是唯一裁决者

```sql
UPDATE room_inventory
   SET sold_rooms = sold_rooms + ?, version = version + 1
 WHERE room_type_id = ? AND stay_date = ?
   AND sold_rooms + ? <= total_rooms      -- ← 判断和修改在同一条语句里
```

判断和修改在一条 SQL 里，**没有"先查再改"的窗口**。

Redis 只是加速层：它答"够不够"是为了少查库，真打起来还是数据库说了算。

### 3. 台账是真相，`sold_rooms` 只是可重建的汇总

```
booking_inventory_link（台账）   每笔订单占用了哪个房型的哪一天、几间、是否已释放
        ↓ 汇总
room_inventory.sold_rooms        可重建的聚合值
        ↓ 派生
Redis 缓存                       可随时删掉
```

箭头只能向外。所以任何一层坏了都能从台账重建 —— 测试里有一条专门的"零漂移"检查，全表比对台账汇总与 `sold_rooms`。

### 4. 状态码 90 与 91 分开

```
90  CANCELLED   用户主动取消（按退订政策算钱，可能扣费）
91  EXPIRED     待支付超时，系统释放（全额、不扣钱、cancel_role = SYSTEM）
```

合成一个"已取消"会让退款逻辑和统计口径全部含糊 —— 而这恰好是最常被财务问到的问题。

### 5. 身份只来自凭证，不来自入参

`userId` **永远从 token 解出来**（`UserContext`），请求体里的 `userId` 被忽略。

订单的读写都校验归属，不是自己的返回 **403**；运维接口另外要求管理员。

---

## 功能一览

| 模块 | 内容 |
|---|---|
| **订单** | 下单 / 支付 / 取消（退订政策）/ 改期（只动台账差集）/ 超时失效 |
| **库存** | 区间库存、条件 UPDATE 预占、Redis 缓存（Lua 原子预占 + 回滚补偿） |
| **幂等** | `@Idempotent` + `SET NX EX` + 结果回放；指纹里含登录用户，不会跨用户串单 |
| **延迟队列** | Redis ZSet + Lua 原子认领 + 扫表兜底（双保险） |
| **附近** | Redis GEO（单 key）、`GEOSEARCH`、启动预热、候选池放大 |
| **缓存治理** | 穿透（空值标记）/ 击穿（单飞 + 锁）/ 雪崩（TTL 抖动） |
| **价格日历** | 按月 Hash 缓存、改价即时失效、无价格日期明确报错 |
| **鉴权** | 登录 / token / 拦截器 / 越权 403 / 运维接口要管理员 |
| **Agent** | 意图路由、槽位多轮、工具调用、**HITL 确认后才下单** |

---

## 接口一览

共 **27 个**接口。除 `/auth/login` 外都需要 `Authorization: Bearer <token>`；标 🔒 的需要管理员。

### 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/login` | 登录，返回 token（免鉴权） |
| POST | `/auth/logout` | 退出，token 立刻失效 |
| GET | `/auth/me` | 我是谁 |

### 预订

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/booking/availability` | 查某房型某区间有没有房 |
| POST | `/booking/quote` | 报价（逐晚累加） |
| POST | `/booking` | 下单（幂等） |
| GET | `/booking/my` | 我的订单 |
| GET | `/booking/{bookingId}` | 订单详情（越权 403） |
| GET | `/booking/user/{userId}` | 某人的订单（越权 403） |
| POST | `/booking/{bookingId}/pay` | 支付 |
| POST | `/booking/{bookingId}/cancel` | 取消 |
| POST | `/booking/{bookingId}/reschedule` | 改期 |
| POST | `/booking/expire-scan` 🔒 | 手动批量释放超时订单 |

### 酒店

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/booking/hotel/nearby` | 附近酒店（GEO + 排序 + 有房过滤） |
| POST | `/booking/hotel/price/update` 🔒 | 改某天房价 |
| POST | `/booking/hotel/geo/rebuild` 🔒 | 重建 GEO 索引 |
| GET | `/booking/hotel/geo/stat` 🔒 | GEO 索引状态 |

### 库存缓存（运维）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/booking/inv/rebuild` 🔒 | 从 DB 重建缓存 |
| GET | `/booking/inv/compare` 🔒 | 缓存与 DB 比对 |
| GET | `/booking/inv/dump` 🔒 | 导出某区间缓存值 |

### 延迟队列（运维）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/booking/delay/stat` 🔒 | 队列状态 |
| POST | `/booking/delay/rebuild` 🔒 | 从 DB 重建队列 |
| POST | `/booking/delay/sweep` 🔒 | 立即扫表兜底一次 |

### 维护（运维）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/booking/maintenance/stat` 🔒 | 数据量概览 |
| POST | `/booking/maintenance/cleanup` 🔒 | 清理历史数据 |

### Agent

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/agent/chat` | 说话（可带 sessionId 续多轮） |
| POST | `/agent/reset` | 丢掉会话 |

---

## 架构

```
                        ┌──────────────────────────────┐
   HTTP  ────────────►  │  拦截器  登录 → 管理员        │
                        └──────────────┬───────────────┘
                                       ▼
                        ┌──────────────────────────────┐
                        │  Controller（27 个接口）      │
                        └──────────────┬───────────────┘
                                       ▼
              ┌────────────────────────┴────────────────────────┐
              ▼                        ▼                        ▼
      ┌───────────────┐        ┌──────────────┐        ┌──────────────┐
      │ BookingService│        │ AgentService │        │ 运维 / Job   │
      │ 下单/支付/改期 │        │ 意图→槽位→工具│        │ 超时·清理    │
      └───────┬───────┘        └──────┬───────┘        └──────┬───────┘
              │                       │                       │
              ▼                       ▼                       ▼
      ┌───────────────┐        ┌──────────────┐        ┌──────────────┐
      │ 库存缓存 + Lua │        │ 会话 + LLM   │        │ 延迟队列 ZSet │
      │ 幂等 / 价格    │        │（只解析意图） │        │              │
      └───────┬───────┘        └──────────────┘        └──────┬───────┘
              │ 降级                                        │
              ▼                                             │
      ┌──────────────────────────────────────────────────┐  │
      │   MySQL：台账（真相）+ room_inventory（汇总）      │◄─┘ 兜底扫表
      │   条件 UPDATE 是唯一裁决者                         │
      └──────────────────────────────────────────────────┘
```

**一条铁律**：Redis 里的东西全部可重建（缓存、队列、会话）；
MySQL 里的台账不可重建，所以它说了算。

---

## 技术栈

| | |
|---|---|
| 语言 / 运行时 | Java 21 |
| 框架 | Spring Boot 3.5.9（Web / Validation / AOP / Data-Redis） |
| 持久层 | MyBatis-Plus 3.5.9 |
| 数据库 | MySQL 8 |
| 缓存 / 队列 / GEO | Redis 7 |
| 口令 | spring-security-crypto（只用 BCrypt，不引入整套 Security） |
| 大模型 | DeepSeek（OpenAI 兼容格式，换模型只改配置） |
| 其它 | Lombok、Jackson |

---

## 快速开始

### 依赖

Java 21、Maven 3.9+、MySQL 8、Redis 7。

### 1. 建库

```bash
mysql -uroot -p < db/schema.sql
mysql -uroot -p < db/seed-p0.sql
```

已有库升级：按顺序跑 `db/migration-p1.sql` → `migration-p2.sql` → `migration-p4.sql`。

种子包含 5 家酒店、6 个房型、180 天价格日历，以及 3 个账号（密码都是 `123456`）：

| 账号 | 角色 | 用途 |
|---|---|---|
| `testuser` | USER | 普通用户 |
| `otheruser` | USER | 验证越权 |
| `admin` | ADMIN | 运维接口 |

### 2. 配置

默认连 `127.0.0.1:13306`（MySQL）和 `127.0.0.1:16379`（Redis），
可用环境变量覆盖：`DB_HOST` / `DB_PORT` / `REDIS_HOST` / `REDIS_PORT` 等。

Agent 需要大模型 key —— **不配置也能启动**，只是 `/agent/chat` 返回 503：

```bash
export DEEPSEEK_API_KEY=sk-xxxx
```

> ⚠️ **key 不要写进仓库**。`application.yaml` 里只有占位符 `${DEEPSEEK_API_KEY:}`。

### 3. 启动

```bash
mvn spring-boot:run
```

### 4. 试一下

```bash
# 登录
curl -X POST localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"testuser","password":"123456"}'
# → {"code":200,"data":{"token":"...","userId":9001,"role":"USER"}}

TOKEN=...

# 查可订
curl "localhost:8080/booking/availability?roomTypeId=2001&checkIn=2026-11-20&checkOut=2026-11-22&roomCount=1" \
  -H "Authorization: Bearer $TOKEN"

# 用自然语言订房
curl -X POST localhost:8080/agent/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"帮我订杭州11月20号住两晚，要大床房"}'
# → {"data":{"stage":"SEARCHED","reply":"找到 2 个可订的房型…","candidates":[…]}}

# 确认（带上一步返回的 sessionId）
curl -X POST localhost:8080/agent/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sessionId":"<上一步的>","message":"确认"}'
# → {"data":{"stage":"BOOKED","bookingId":2120…,"reply":"订好了！订单号 …"}}
```

---

## Agent 是怎么做的

```
用户一句话
  → ① 大模型解析成 {意图, 槽位}        ← 只有这一步用模型
  → ② 合并会话里已有的槽位（多轮）
  → ③ 缺什么就用模板话术追问
  → ④ 齐了调工具：按城市查酒店 → 查可订 → 报价 → 出候选
  → ⑤ 存成【草案】，等用户说"确认"
  → ⑥ 确认 → 用草案下单 → 返回订单号
```

**核心判断：只有第 ① 步用模型。**

模型擅长"把自然语言变成结构化参数"，不擅长也不该做业务计算。
日期怎么换算、库存够不够、总价多少 —— 用代码算：结果确定、可测试、不花钱。

实测这条链路的解析效果：

```
"帮我订杭州11月20号住两晚，要大床房，1间"
  → BOOK / 杭州 / 2026-11-20 → 2026-11-22 / 1间 / 大床

"明天杭州有房吗"     → QUERY / 杭州 / 2026-11-12（相对日期换算正确）
"算了不订了"         → CANCEL
"我想订房"           → BOOK，且知道自己缺 city / checkIn / checkOut
```

### ★ 三条安全线（第 ⑥ 步）

1. **下单内容只来自草案** —— 不来自用户这句话，也不来自模型。
   否则"订那个 100 块的"就能把订单引到别处去。
2. **身份只来自 `UserContext`** —— 模型永远不参与。
3. **草案用完即作废** —— 连点两次"确认"不会下两单；草案超过 5 分钟也作废（价格和房态会变）。

测试里有一条硬断言：草案指向 2001，而"重新搜一次"会选中 2003（更便宜）——
**最终订的必须是 2001**，证明内容真的来自草案。

### 模型不可全信

即使只让它解析意图，服务端也要二次校验：过去的日期、`离店 ≤ 入住`、坏 JSON、
格式错误 —— 全部当作"没听懂"处理并重新追问，而不是信任后往下传。

---

## 项目结构

```
src/main/java/com/fancy/hotel/
├─ common/        统一返回、业务异常、UserContext、幂等注解、Redis key 常量
├─ config/        拦截器（登录 / 管理员）、幂等 AOP、Lua 脚本
├─ controller/    HTTP 入口（27 个接口）
├─ domain/        entity / dto / vo / enums，Agent 会话与草案
├─ mapper/        MyBatis-Plus
├─ service/       业务主体
│  ├─ impl/       BookingServiceImpl（下单 / 支付 / 取消 / 改期 / 超时）
│  └─ agent/      LLM 客户端、会话存储、搜索工具、Agent 编排
└─ job/           超时扫描、定期清理
src/main/resources/
├─ lua/           inventory_reserve / inventory_release / delay_claim / lock_unlock
└─ application.yaml
db/               schema.sql、seed-p0.sql、migration-p*.sql
docs/             设计文档（见下）
test/             端到端测试脚本
```

---

## 测试

测试是**端到端的 PowerShell 脚本**，不是单元测试 —— 因为这些内容
（并发、缓存、死锁、幂等、越权）只有真的打接口才看得见。

```powershell
cd test
powershell -NoProfile -ExecutionPolicy Bypass -File .\p0_flow_test.ps1
```

| 套件 | 覆盖 |
|---|---|
| `p0_flow_test` | 下单→支付→取消 / 超卖拒绝 / 超时释放 |
| `p0_1_reschedule_test` | 改期：延长 / 平移 / 缩短 / 错开 / 回滚 |
| `p1_inventory_cache_test` | 缓存与 DB 一致性、冷缓存降级、跨月 |
| `p1_idempotency_test` | 重复提交只下一单、结果回放 |
| `p2_delay_queue_test` | 延迟队列认领、扫表兜底 |
| `p2_geo_test` / `p2_nearby_filter_test` | GEO 检索、排序与有房过滤 |
| `p3_*` | 穿透 / 击穿 / 雪崩 / 价格缓存 |
| `p4_auth_test` / `p4_authz_test` | 登录、越权 403、运维接口要管理员 |
| `p4_agent_test` / `p4_agent_hitl_test` | Agent 多轮、确认后下单（演示套件） |

**14 个稳定套件共 301 项断言**，是提交前必须全绿的回归门槛。

### Agent 评测（衡量"准不准"，不是"通不通"）

端到端测试只能告诉你流程还走得通，**不能告诉你意图判断准不准**。所以另外有一层评测：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\p4_agent_eval.ps1
```

39 条用例覆盖意图 / 槽位 / 相对日期 / 缺字段 / 口语错别字 / 无关信息混入 / 多意图 / 超长句，
逐条比对**期望意图 + 期望槽位 + 期望阶段**，输出三个指标（报告见 `test/eval-report.md`）：

| 指标 | 基线 |
|---|---|
| 意图准确率 | 39/39 = 100% |
| 槽位字段准确率 | 87/87 = 100% |
| 阶段准确率 | 31/32 = 96.9% |
| **端到端通过** | **38/39 = 97.4%** |
| 延迟 | p50 = 2.1s，p95 = 5.8s |

> 每条用例用**全新会话**，测的是模型的理解能力，不是上一轮的残留。
> 唯一未通过的是"设施问答"（"有停车场吗"）—— 那是 RAG 要解决的，已作为已知缺口留在集子里。
两个 `p4_agent*` 是能力演示套件，依赖外部大模型，不作为门槛（详见 `test/README.md`）。

---

## 文档

| 文件 | 内容 |
|---|---|
| `docs/01-domain-model.md` | 领域模型：为什么按天存、状态机、台账设计 |
| `docs/02-P0总结.md` | P0：区间库存的并发正确性（条件 UPDATE、左闭右开） |
| `docs/03-P1总结.md` | P1：缓存与 DB 的最终一致性、幂等 |
| `docs/04-P2总结.md` | P2：延迟队列、GEO、排序过滤 |
| `docs/05-P3总结.md` | P3：穿透 / 击穿 / 雪崩，以及那个 S→X 锁升级死锁 |
| `docs/06-P4总结.md` | P4：鉴权、越权、Agent 与 HITL |

---

## 开发过程中挖到的几个真 bug

这几个不是"设计得漂亮"，是**测试和回归真的抓出来的**，也都写进了对应文档：

| bug | 现象 | 原因 |
|---|---|---|
| **S→X 锁升级死锁** | 并发下单偶发 500 | `INSERT IGNORE` 会在重复行上留共享锁，后续 `UPDATE` 需要排他锁 → 互相等待 |
| **Redis 一抖就 500** | 缓存超时时下单失败 | 有两处 Redis 调用没做降级；缓存坏了应退化为"慢"，不应变成"错" |
| **跨用户幂等串单** | 两人提交相同请求体，后者拿到前者的订单号 | 幂等指纹只序列化入参，而 `userId` 改成了从 token 取 |

这三条也是本项目最值得看的部分 —— 它们只在**并发 + 真实回归**下才会露出来。

---

## 已知边界

- **支付是假的**：`pay` 只改状态，没有接真实支付网关
- **Agent 只支持预订**：取消 / 改期会如实告诉用户"这个还不会，请去订单页操作"
- **大模型是演示级**：两个 Agent 测试套件依赖外部 API，连跑全量时可能抖动，不追求高并发
- **开发环境经 SSH 隧道访问远端 Redis**：十几路并发时单条命令可能排队几秒，
  所以 `application.yaml` 里 Redis 超时放宽到 10 秒（生产同机房可调回 3 秒）
- **Mongo / Elasticsearch 未使用**：环境已就绪，预留给后续的工单与检索
