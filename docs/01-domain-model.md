# hotel-booking 领域模型

> 配套文件：[`db/schema.sql`](../db/schema.sql)（当前只含 **P0 的 8 张表**）
> 状态：**设计已 review 通过，尚未执行到服务器**
> 部署目标：`<your-server>` 的 `taxi-mysql` 容器，本地经 SSH 隧道 `127.0.0.1:13306` 访问

---

## 0. 已确认的设计决定

| # | 决定 | 说明 |
|---|---|---|
| 1 | 入住区间 **左闭右开** `[check_in, check_out)` | 见第 2 节 |
| 2 | 状态码**区分详细**：`90 已取消` / `91 已失效` | 见第 6 节 |
| 3 | 幂等用**独立表** `idempotent_record` | 见第 9 节（P1 实现） |
| 4 | **一步步来，先 P0** | 见第 11 节 |
| 5 | 取消政策先**枚举硬编码** | 见第 7 节（但保留订单快照） |

---

## 1. 一句话

> **把"房型 × 日期区间"作为库存单元**。这和 TaxiAgent 的"一个订单要么可抢要么不可抢"（标量库存）是**本质区别** —— 也是本项目相对它最大的升级点。

---

## 2. ★ 核心一：区间语义（左闭右开）

```
订单：入住 7/1，离店 7/4
占用：[7/1, 7/4)  =  7/1、7/2、7/3      ← 7/4 那天【不占用】
```

**为什么**：客人 7/4 早上就走了，7/4 晚上这张床可以卖给别人。

**写错会怎样**：写成闭区间会**每晚多占一天**，而且订单越长偏差越大（住 30 晚就多占 30 天）。

`nights = check_out - check_in`。这条规则必须贯穿**下单、算价、库存扣减、释放、改期**所有环节 —— 任何一处写错都会造成库存漂移，而且是静默的。

---

## 3. ★ 核心二：为什么按天分行

**表**：`room_inventory`，`UNIQUE(room_type_id, stay_date)`

```
room_type_id = RT01（大床房，总 3 间）
┌────────────┬─────────────┬────────────┐
│ stay_date  │ total_rooms │ sold_rooms │
├────────────┼─────────────┼────────────┤
│ 2026-07-01 │      3      │     1      │  ← 订单 A
│ 2026-07-02 │      3      │     2      │  ← 订单 A + B
│ 2026-07-03 │      3      │     2      │
│ 2026-07-04 │      3      │     1      │  ← 订单 B
└────────────┴─────────────┴────────────┘
```

**"能不能订"退化成一句判断**：区间内每一天都满足 `sold_rooms + 需求间数 <= total_rooms`。

**如果改成"一行管一个区间"**：判断重叠要扫描该房型的所有历史区间（`WHERE NOT (check_out <= ? OR check_in >= ?)`），热点房型下性能极差，且随区间数量增长而持续恶化。

**代价**：一次预订要操作 N 行（N = 晚数）。住 5 晚就是 5 行 —— 这正是并发方案要处理的东西。

---

## 4. ★ 核心三：库存扣减的两阶段 + 回滚

沿用 TaxiAgent 的哲学（**缓存只做加速，DB 才是裁决者**），但复杂度高一档：

```
① Redis Lua 原子预检 + 预占
     区间内每一天「检查 + 扣减」，在同一个 Lua 内完成
     ★ 必须两趟: 先全查，全部通过才全扣
        （边查边扣的话，第 3 天不够时前 2 天已经扣了，Lua 里做补偿很别扭）
     任何一天不满足 → 一个都不扣，返回失败
                ↓ 通过
② MySQL 逐天条件 UPDATE（包在同一事务里）
     UPDATE room_inventory
     SET sold_rooms = sold_rooms + ?, version = version + 1
     WHERE room_type_id = ? AND stay_date = ?
       AND sold_rooms + ? <= total_rooms
     任何一天影响 0 行 → 抛异常 → 【整个事务回滚】
                ↓ 成功
③ 写入 booking_inventory_link 台账 + 同步 Redis
```

**回滚比 TaxiAgent 难**：TaxiAgent 回滚是"把 `driver:active` 设回哨兵值"（**单值**）；这里要把**区间内每一天**都还回去。

所以需要 `booking_inventory_link` 记录"到底占了哪几天"，回滚/释放时**按台账逐天还原**，而不是重新推导区间（推导容易出错，且改期场景下原始区间已经变了）。

---

## 5. ★ 核心四：`booking_inventory_link` 为什么必须存在

| 用途 | 说明 |
|---|---|
| **精确释放** | 取消/失效时按记录逐天 `sold_rooms - room_count`，不用重算区间 |
| **部分取消** | 订 3 晚、取消后 2 晚 → 只释放对应那 2 天的记录 |
| **对账** | 直接查"某一天被哪些订单占了" —— 排查超卖时是唯一线索 |

**和 Redis 预占的关系**：Redis 里的区间库存是**副本**（可能被淘汰、可能不一致），这张表是 DB 侧的**台账**，更可靠。

---

## 6. 状态机

```
10 待支付(PENDING_PAYMENT)
      │ 支付成功
      ↓
20 已确认(CONFIRMED) ──入住──> 30 已入住(CHECKED_IN) ──退房──> 40 已完成(COMPLETED)
      │
      │ 取消（用户/客服）                    [10 超时未支付 → 91]
      ↓
90 已取消(CANCELLED)              91 已失效(EXPIRED)
```

| 状态码 | 含义 | 谁能进入 | 库里的标记 |
|---|---|---|---|
| 10 | 待支付 | 下单 | — |
| 20 | 已确认 | 支付成功 | `pay_time` |
| 30 | 已入住 | 前台/系统 | `check_in_time` |
| 40 | 已完成 | 退房 | `check_out_time` |
| **90** | **已取消** | **用户或客服主动** | `cancel_role` = 1 或 2 |
| **91** | **已失效** | **系统：15 分钟未支付** | `cancel_role` = 3 |

**为什么拆成 90 / 91**（而不是像 TaxiAgent 那样都记 90 + `cancel_role`）：

- 退款报表要分开统计"**未支付失效**"（没有资金流）和"**主动取消**"（可能有退款）
- 两者的库存释放时机不同（失效是定时触发，取消是用户触发）
- 运营指标里"未支付率"和"取消率"是两个独立 KPI

---

## 7. 取消政策：**枚举硬编码** + 订单快照

### 阶梯规则放 Java 枚举

```java
// 计划中的形态（P0 用这个）
public enum CancelPolicy {
    FREE_CANCEL(1, "免费取消", List.of(
            new Tier(0,  new BigDecimal("1.000")))),

    LIMITED_FREE(2, "限时免费", List.of(
            new Tier(168, new BigDecimal("1.000"))),   // 提前 >=7 天  全退
            new Tier(72,  new BigDecimal("0.800"))),   // 提前 3~7 天 退 80%
            new Tier(24,  new BigDecimal("0.500"))),   // 提前 1~3 天 退 50%
            new Tier(0,   new BigDecimal("0.000")))),  // 24 小时内   不退

    NON_REFUNDABLE(3, "不可取消", List.of(
            new Tier(0, new BigDecimal("0.000"))));
}
```

`room_type.cancel_policy` 存枚举码（1/2/3）。

**为什么先硬编码**：P0 阶段业务规则还没稳定，做成表反而要反复改数据。等规则稳定了再数据化 —— **换的时候只需把"查枚举"改成"查表"，快照机制不用动**。

### ★ 但订单仍要存快照

`booking.cancel_policy_snapshot`（JSON：政策码 + 当时的阶梯规则）：

```
用户 6/1 下单（当时：提前 3 天免费取消）
运营 6/10 改了枚举把门槛改成"提前 7 天"（发版）
用户 6/15 要取消（入住 6/20，提前 5 天）

读【当前枚举】→ 不能免费退 ❌  用户投诉"我下单时不是这么说的"
读【订单快照】→ 可以免费退 ✅  正确
```

**下单那一刻的规则要冻结在订单上** —— 酒店/机票类业务的通用做法。**硬编码枚举 ≠ 可以不做快照**，这是两件事。

---

## 8. 金额计算：逐晚累加

`room_price` 是按天的，所以：

```
总价 = Σ(入住区间内每天的价格) × 间数
```

**不是"一晚均价 × 晚数"**，因为周末/工作日价格不同：

```
7/1(周三) 400 + 7/2(周四) 400 + 7/3(周五) 600 = 1400   ✅ 逐晚累加
均价 466.67 × 3 = 1400.01                              ❌ 舍入误差
```

**逐晚累加、最后统一 `setScale(2, HALF_UP)`**，避免"先算均价再乘"的精度问题。

---

## 9. 幂等：独立表 + 两层防御（P1 实现）

**表**：`idempotent_record`（P1 阶段建）

| 层 | 手段 | 作用 |
|---|---|---|
| 第一层 | Redis `SET idem:{key} 1 NX EX 10` | 快速挡住重复提交（绝大多数请求在这一层被拦） |
| 第二层 | `idempotent_record.uk_idem_key` UNIQUE | **Redis 挂了、或跨实例**时的落库兜底，还能回放首次结果 |

**幂等键构成**：`用户ID + 业务类型 + 关键参数指纹`

```
userId:CREATE_BOOKING:hotelId=H1:roomTypeId=RT1:checkIn=2026-07-01:checkOut=2026-07-04:rooms=1
```

**为什么用独立表而不是在 `booking` 上加个字段**：

- 幂等是**通用机制**（下单、支付、取消、改期都要用），不该绑在某一张业务表上
- 能存 `result_ref` 做**结果回放**（重复请求直接返回首次的 `booking_id`，而不是报错）
- `booking` 不加冗余列，保持表结构干净

> **这是补 TaxiAgent 的空白** —— 它的 `docs/learn/redis/README.md` 里"未实现的考点"表格明确写着「接口幂等（通用机制）：无幂等注解/切面」。酒店下单场景**天然需要**（用户在支付页手抖点两下 = 下两张单 = 占两次库存）。

---

## 10. 与 TaxiAgent 的对照

| TaxiAgent | hotel-booking | 变化 |
|---|---|---|
| — | `hotel` / `room_type` | 新增 |
| — | **`room_inventory`** | ★ 全新：按天库存 |
| — | **`room_price`** | ★ 全新：价格日历 |
| `sys_ride_order` | `booking` | ★ 单点 → **区间**；加政策快照、取消方标记 |
| — | **`booking_inventory_link`** | ★ 全新：占用台账 |
| `sys_user` | `sys_user` | ★ **补了 3 个唯一索引**（它原本一个都没有） |
| `sys_user_poi` | `sys_user_poi` | 加 `UNIQUE(user_id, poi_tag)` |
| — | `idempotent_record`（P1） | ★ 全新：通用幂等，补它的空白 |
| `sys_chat` / `sys_messages` | 同名（P4） | 基本不变 |
| `sys_chat_tool_repsonse` | `sys_chat_tool_response`（P4） | ★ **修正表名拼写** |
| `sys_ticket` / `sys_ticket_chat` | `ticket` / `ticket_chat`（P5） | 关联字段改为 `booking_id` |
| `sys_qa_info` / `sys_qa_es` | `qa_info` / `qa_es`（P5） | 加 `category` 分类字段 |

---

## 11. 分阶段：每阶段建哪些表

| 阶段 | 内容 | 表 | 数量 |
|---|---|---|---|
| **P0** ✅ 当前 | 领域模型 + 状态机骨架 | `hotel`、`room_type`、**`room_inventory`★**、`room_price`、`booking`、**`booking_inventory_link`★**、`sys_user`、`sys_user_poi` | **8** |
| P1 | 区间库存 Lua + 回滚 + **幂等** | `idempotent_record` | +1 |
| P2 | 延迟队列释放 + GEO 附近酒店 | 复用 P0 | 0 |
| P3 | 缓存三兄弟 + 分布式锁 | 复用 | 0 |
| P4 | Agent：分类 + BookingAgent + HITL | `sys_chat`、`sys_messages`、`sys_chat_tool_response` | +3 |
| P5 | ServiceAgent + 工单 + RAG | `ticket`、`ticket_chat`、`qa_info`、`qa_es` | +4 |
| | | **合计** | **16** |

> 每个阶段的表在**进入该阶段时**才追加到 `schema.sql`，避免提前冻结结构。

---

## 12. 下一步

schema 已按 5 项决定调整完毕，等你确认后执行：

```
1. CREATE DATABASE hotel_booking
2. 执行 db/schema.sql  → 建 8 张表
3. 校验：SHOW TABLES 应有 8 张，且字符集为 utf8mb4_unicode_ci
```

执行到服务器的方式：本地经 SSH 隧道把 `schema.sql` 送到远端，用 `docker exec taxi-mysql mysql` 执行。
