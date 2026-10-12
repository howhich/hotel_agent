package com.fancy.hotel.domain.enums;

import lombok.Getter;

/**
 * 预订单状态机
 *
 * <pre>
 *   10 待支付 ──支付──> 20 已确认 ──入住──> 30 已入住 ──退房──> 40 已完成
 *      │                    │
 *      │ 15min 超时          │ 用户/客服取消
 *      ↓                    ↓
 *   91 已失效            90 已取消
 * </pre>
 *
 * <p>★ 与 TaxiAgent 的差异：它把「系统超时」和「用户取消」都记成 90（用 cancelRole 区分），
 * 本项目拆成 90 / 91 两个状态码 —— 退款报表要分开统计「未支付失效」和「主动取消」。
 */
@Getter
public enum BookingStatus {

    PENDING_PAYMENT(10, "待支付"),
    CONFIRMED(20, "已确认"),
    CHECKED_IN(30, "已入住"),
    COMPLETED(40, "已完成"),
    CANCELLED(90, "已取消"),
    EXPIRED(91, "已失效");

    private final int code;
    private final String desc;

    BookingStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static BookingStatus fromCode(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("订单状态不能为空");
        }
        for (BookingStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("非法的订单状态: " + code);
    }

    /** 终态：不会再变化 */
    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == EXPIRED;
    }

    /** 是否仍占着库存（终态里只有"已完成"是正常消费掉，不算占用） */
    public boolean holdsInventory() {
        return !terminal();
    }

    /** 是否可取消（只有待支付和已确认可以） */
    public boolean cancellable() {
        return this == PENDING_PAYMENT || this == CONFIRMED;
    }

    /** 是否可支付（只有待支付可以） */
    public boolean payable() {
        return this == PENDING_PAYMENT;
    }
}
