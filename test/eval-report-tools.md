# Agent 评测报告（mode = ）

> 由 `test/p4_agent_eval.ps1` 自动生成，每次运行覆盖。
> 每个用例用一个**全新会话**，所以测的是模型的理解能力，不是上一轮的残留。

- 运行时间：2026-11-12 15:46
- 用例数：39（其中 HTTP 失败 0 条）

| 指标 | 结果 |
|---|---|
| 意图准确率 | 31/39 = 79.5% |
| 槽位字段准确率 | 87/87 = 100.0% |
| 阶段准确率 | 29/32 = 90.6% |
| **端到端通过** | **28/39 = 71.8%** |
| 延迟 | p50 = 2.56s，p95 = 6.10s |

## 失败明细（11 条）

| 用例 | 说法 | 期望 | 实际 | 槽位差异 |
|---|---|---|---|---|
| `book-twin-one-night` | 杭州 2026-11-21 住一晚，要双床房 | intent=BOOK / stage=SEARCHED | intent=QUERY / stage=SEARCHED | — |
| `book-three-nights` | 2026-11-21 去杭州住三晚，要一间大床房 | intent=BOOK / stage=SEARCHED | intent=QUERY / stage=SEARCHED | — |
| `rel-day-after-tomorrow` | 后天杭州有房间吗 | intent=QUERY / stage=COLLECTING | intent=QUERY / stage=SEARCHED | — |
| `intent-cancel-vague` | 把订单取消了吧 | intent=CANCEL | intent=MYBOOKINGS / stage=NO_RESULT | — |
| `slot-bed-and-rooms` | 杭州 2026-11-21 到 2026-11-23，要两间大床房 | intent=BOOK / stage=SEARCHED | intent=QUERY / stage=SEARCHED | — |
| `slot-checkout-explicit` | 杭州 2026-11-21 入住，2026-11-23 退房，要大床房 | intent=BOOK / stage=SEARCHED | intent=QUERY / stage=SEARCHED | — |
| `hard-day-after-tomorrow-cn` | 大后天杭州有房吗 | intent=QUERY / stage=COLLECTING | intent=QUERY / stage=SEARCHED | — |
| `hard-next-saturday` | 这周六杭州住一晚，要大床房 | intent=BOOK / stage=SEARCHED | intent=QUERY / stage=SEARCHED | — |
| `hard-multi-intent` | 杭州 2026-11-21 到 2026-11-23 有房吗？有的话就帮我订一间大床房 | intent=BOOK / stage=SEARCHED | intent=QUERY / stage=SEARCHED | — |
| `hard-long-sentence` | 是这样的，我这周要去杭州出差三天，想找个离市区近一点的酒店，2026-11-21 入住，2026-11-23 走，一间大床房就行，麻烦了 | intent=BOOK / stage=SEARCHED | intent=QUERY / stage=SEARCHED | — |
| `gap-facility-question` | 杭州西湖国宾馆有停车场吗？能带宠物吗 | intent=QUERY / stage=UNSUPPORTED | intent=QUERY / stage=COLLECTING | — |
