# hotel-booking

一个**酒店/民宿预订**后端，重点解决两件真实项目里最容易出错的事：

1. **区间库存**（房型 × 日期区间）的高并发正确性
2. **Agent 接入**（自然语言订房 + 人工确认），且不让模型决定订单内容

> 灵感来自 [TaxiAgent](https://github.com/ZhuofanWu/TaxiAgent) 的"并发 + Agent"架构。
> TaxiAgent 做的是出租车（**标量库存**：一个城市有 N 辆车），
> 这个项目换成酒店（**区间库存**：一个房型在 [入住, 离店) 这段日期里每天各有 N 间），
> 难度和要处理的问题都不一样 —— 这是本项目的核心差异点。

---

## 一句话看懂最难的地方

**"还剩几间房"不能用计数器。**

酒店库存的天然形状是"按天"的：11-20 住到 11-22，占的是 11-20 和 11-21 两天，
11-22 那天是别人的。所以：

```
房型 2001，总 3 间
           11-19  11-20  11-21  11-22
sold_rooms   1      2      3      0     ← 每天一行，UNIQUE(room_type_id, stay_date)
```

一个 2 晚的订单要**同时**在 2 行上成功；任何一行不够就整体失败、一行都不能留。
这就是"区间库存"，也是本项目所有复杂度的源头。

---

## 五个关键设计

### 1. 左闭右开 `[checkIn, checkOut)`

`11-20 到 11-22` 占 **2 天**（11-20、11-21），**不含离店日**。
全项目（库存、价格、台账、测试）都遵守这一条，否则边界永远差一天。

### 2. 条件 UPDATE 是唯一裁决者

```sql
UPDATE room_inventory
   SET sold_rooms = sold_rooms + ?, version = version + 1
 WHERE room_type_id = ? AND stay_date = ?
   AND sold_rooms + ? <= total_rooms      -- ← 判断和修改在同一条 SQL 里
```

判断和修改在一条语句里，**没有"先查再改"的窗口**。
Redis 只是加速层：它答"够不够"是为了少查库，真打起来还是数据库说了算。

### 3. 台账是真相，`sold_rooms` 只是可重建的汇总

```
booking_inventory_link（台账）：每笔订单占用了哪个房型的哪一天、几间、有没有释放
        ↓ 汇总
room_inventory.sold_rooms（可重建）
        ↓ 派生
Redis 缓存（可重建，删了也不影响正确性）
```

箭头只能向外。所以任何一层坏了都能从台账重建 —— 测试里专门有一条"零漂移"检查。

### 4. 状态码 90 与 91 分开

```
90 CANCELLED   用户主动取消（按退订政策算钱）
91 EXPIRED     待支付超时，系统释放（全额、不扣钱）
```

合成一个"已取消"会让退款逻辑和统计口径全部含糊。

### 5. 身份只来自凭证，不来自入参

`userId` **永远从 token 解出来**（`UserContext`），请求体里的 `userId` 被忽略。
订单的读写都校验归属，不是自己的返回 **403**。

---

## 功能清单

| 模块 | 内容 |
|---|---|
| **订单** | 下单 / 支付 / 取消（退订政策）/ 改期（只动台账差集）/ 超时失效 |
| **库存** | 区间库存、条件 UPDATE 预占、Redis 缓存（Lua 原子预占 + 回滚补偿） |
| **幂等** | `@Idempotent` + Redis `SET NX EX` + 结果回放（指纹里含登录用户） |
| **延迟队列** | Redis ZSet + Lua 原子认领，配"扫表"兜底双保险 |
| **附近** | Redis GEO（单 key）、`GEOSEARCH`、启动预热 |
| **排序过滤** | 距离/价格/评分 + 有房过滤（候选池自动放大） |
| **缓存三兄弟** | 穿透（空值标记）/ 击穿（单飞 + 锁）/ 雪崩（TTL 抖动） |
| **价格日历** | 按月 Hash 缓存 + 改价即时失效 |
| **鉴权** | 登录 / token / 拦截器 / 越权 403 / 运维接口要管理员 |
| **Agent** | 意图路由、槽位多轮、工具调用、**HITL 确认后才下单** |

---

## 快速开始

### 依赖

- Java 21、Maven 3.9+
- MySQL 8、Redis 7（本项目开发时跑在远端，本地经隧道访问）

### 1. 建库

```bash
mysql -uroot -p < db/schema.sql
mysql -uroot -p < db/seed-p0.sql
```

已有库升级：按顺序跑 `db/migration-p1.sql`、`migration-p2.sql`、`migration-p4.sql`。

### 2. 配置

默认连 `127.0.0.1:13306`（MySQL）和 `127.0.0.1:16379`（Redis），
可用环境变量覆盖：`DB_HOST` / `DB_PORT` / `REDIS_HOST` / `REDIS_PORT` 等。

Agent 需要大模型 key（**不配置也能启动**，只是 `/agent/chat` 返回 503）：

```bash
export DEEPSEEK_API_KEY=sk-xxxx      # 或者 Windows 下 $env:DEEPSEEK_API_KEY='sk-xxxx'
```

> ⚠️ **key 不要写进仓库**。`application.yaml` 里只有占位符 `${DEEPSEEK_API_KEY:}`。

### 3. 启动

```bash
mvn spring-boot:run
```

### 4. 试一下

```bash
# 登录（种子里有 testuser / otheruser / admin，密码都是 123456）
curl -X POST localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"testuser","password":"123456"}'
# → {"code":200,"data":{"token":"...","userId":9001,...}}

TOKEN=...

# 查可订
curl "localhost:8080/booking/availability?roomTypeId=2001&checkIn=2026-11-20&checkOut=2026-11-22&roomCount=1" \
  -H "Authorization: Bearer $TOKEN"

# 用自然语言订房
curl -X POST localhost:8080/agent/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"帮我订杭州11月20号住两晚，要大床房"}'
# → {"data":{"stage":"SEARCHED","reply":"找到 2 个可订的房型...","candidates":[...]}}

# 确认（带上返回的 sessionId）
curl -X POST localhost:8080/agent/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sessionId":"<上一步返回的>","message":"确认"}'
# → {"data":{"stage":"BOOKED","bookingId":2120...,"reply":"订好了！订单号 ..."}}
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
  → ⑥ 用户确认 → 用草案下单 → 返回订单号
```

**核心判断：只有第 ① 步用模型。**

模型擅长"把自然语言变成结构化参数"，不擅长也不该做业务计算。
日期怎么换算、库存够不够、总价多少 —— 用代码算：结果确定、可测试、不花钱。

### ★ 三条安全线（第 ⑥ 步）

1. **下单内容只来自草案**，不来自用户这句话、也不来自模型。
   否则"订那个 100 块的"就能把订单引到别处去。
2. **身份只来自 `UserContext`**，模型永远不参与。
3. **草案用完即作废**，连点两次"确认"不会下两单；草案超过 5 分钟也作废（价格房态会变）。

测试里有一条硬断言：草案指向 2001，而"重新搜一次"会选中 2003（更便宜）——
最终订的必须是 **2001**，证明内容真的来自草案。

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
│  ├─ impl/       BookingServiceImpl（下单/支付/取消/改期/超时）
│  └─ agent/      LLM 客户端、会话存储、搜索工具、Agent 编排
└─ job/           超时扫描、定期清理
```

---

## 文档

| 文件 | 内容 |
|---|---|
| `docs/01-domain-model.md` | 领域模型：为什么按天存、状态机、台账设计 |
| `docs/02-P0总结.md` | P0：区间库存的并发正确性（条件 UPDATE、左闭右开） |
| `docs/03-P1总结.md` | P1：Redis 缓存与 DB 的最终一致性、幂等 |
| `docs/04-P2总结.md` | P2：延迟队列、GEO、排序过滤 |
| `docs/05-P3总结.md` | P3：穿透 / 击穿 / 雪崩，以及那个 S→X 锁升级死锁 |
| `docs/06-P4总结.md` | P4：鉴权、越权、Agent 与 HITL |

---

## 测试

测试是**端到端的 PowerShell 脚本**（不是单元测试）——因为这些内容
（并发、缓存、死锁、幂等、鉴权）只有真的打接口才看得见。

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
| `p4_auth_test` / `p4_authz_test` | 登录、越权 403、**运维接口要管理员** |
| `p4_agent_test` / `p4_agent_hitl_test` | Agent 多轮、**确认后才下单**（演示套件） |

**13 个稳定套件共 320 项断言**，是提交前必须全绿的回归门槛。
两个 `p4_agent*` 是能力演示套件，依赖外部大模型，不作为回归门槛（详见 `test/README.md`）。

---

## 已知边界

- **支付是假的**：`pay` 只改状态，没有接真实支付网关
- **Agent 只支持预订**：取消/改期会如实告诉用户"这个还不会，请去订单页操作"
- **开发环境经 SSH 隧道访问远端 Redis**：十几路并发时单条命令可能排队几秒，
  所以 `application.yaml` 里 Redis 超时放宽到了 10 秒（生产同机房可以调回 3 秒）
- **Mongo / Elasticsearch 容器已就绪但未使用**：预留给后续的工单与检索
=======
# hotel_agent
