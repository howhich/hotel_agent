package com.fancy.hotel.domain.enums;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 取消政策（★ 当前阶段硬编码为枚举）
 *
 * <p>阶梯退款规则写在枚举里，而不是做成数据库表。P0 阶段业务规则还没稳定，
 * 做成表反而要反复改数据；等规则稳定了再数据化 —— 那时只需把「查枚举」换成「查表」，
 * <b>订单快照机制不用动</b>。
 *
 * <p>★ 硬编码 ≠ 可以不做快照：下单时仍然把当时的规则写进
 * {@code booking.cancel_policy_snapshot}，否则改了枚举发版后，
 * 所有老订单都会被追溯适用新规则，用户会投诉「我下单时不是这么说的」。
 */
@Getter
public enum CancelPolicy {

    FREE_CANCEL(1, "免费取消",
            List.of(new Tier(0, new BigDecimal("1.000")))),

    LIMITED_FREE(2, "限时免费",
            List.of(
                    new Tier(168, new BigDecimal("1.000")),   // 提前 >=7 天   全退
                    new Tier(72, new BigDecimal("0.800")),    // 提前 3~7 天  退 80%
                    new Tier(24, new BigDecimal("0.500")),    // 提前 1~3 天  退 50%
                    new Tier(0, new BigDecimal("0.000")))),   // 24 小时内    不退

    NON_REFUNDABLE(3, "不可取消",
            List.of(new Tier(0, new BigDecimal("0.000"))));

    private final int code;
    private final String desc;

    /** 按 minHoursBefore 降序排列，refundRatio 依赖这个顺序 */
    private final List<Tier> tiers;

    /**
     * 一档退款规则：距入住至少 {@code minHoursBefore} 小时，按 {@code refundRatio} 退款
     */
    public record Tier(int minHoursBefore, BigDecimal refundRatio) {
    }

    CancelPolicy(int code, String desc, List<Tier> tiers) {
        this.code = code;
        this.desc = desc;
        List<Tier> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparingInt(Tier::minHoursBefore).reversed());
        this.tiers = List.copyOf(sorted);
    }

    public static CancelPolicy fromCode(Integer code) {
        if (code == null) {
            return FREE_CANCEL;
        }
        for (CancelPolicy p : values()) {
            if (p.code == code) {
                return p;
            }
        }
        throw new IllegalArgumentException("未知的取消政策: " + code);
    }

    /**
     * 计算可退比例
     *
     * @param hoursBeforeCheckIn 距入住还有多少小时（负数表示入住日已经开始）
     */
    public BigDecimal refundRatio(long hoursBeforeCheckIn) {
        for (Tier t : tiers) {
            if (hoursBeforeCheckIn >= t.minHoursBefore()) {
                return t.refundRatio();
            }
        }
        // 没命中任何档位 = 已经晚于所有门槛（入住当天或之后才取消）。
        // 回落到最后一个档位（tiers 已按 minHoursBefore 降序，last 就是兜底档）。
        //
        // ★ 不能直接返回 0：像 FREE_CANCEL 这种"任意时间全退"的政策，
        //   兜底档比例是 1.000，返回 0 会让用户一分钱都退不到。
        //   这个 bug 只有"入住当天取消"才会触发。
        return tiers.isEmpty() ? BigDecimal.ZERO : tiers.get(tiers.size() - 1).refundRatio();
    }

    /**
     * 生成下单快照（JSON）
     *
     * <p>手写而不用 Jackson：枚举的静态上下文里注入 ObjectMapper 很别扭，
     * 而这里的内容完全由常量拼成（名字是固定的中文，不含引号），没有转义风险。
     */
    public String snapshotJson() {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"policy\":").append(code)
          .append(",\"name\":\"").append(desc)
          .append("\",\"tiers\":[");
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"minHoursBefore\":").append(t.minHoursBefore())
              .append(",\"refundRatio\":").append(t.refundRatio().toPlainString())
              .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }
}
