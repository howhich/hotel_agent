package com.fancy.hotel.domain.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 待用户确认的草案（P4.4 起，P2 扩展成三种动作）
 *
 * <p>Agent 查到结果之后，把"要做什么"整份存进会话；用户说"确认"时
 * <b>用它执行，而不是用用户这句话里提到的东西</b>。
 *
 * <h3>★ 为什么必须以草案为准</h3>
 * 如果确认时去解析用户那句话（"订那个 100 块的"），模型就可能被引导去做另一件事。
 * 草案是<b>服务端自己查出来、自己算过</b>的那一份，它才可信。
 *
 * <h3>三种动作靠 {@code kind} 区分</h3>
 * <pre>
 *   BOOK        预订：用 hotelId + roomTypeId + checkIn/checkOut + roomCount
 *   CANCEL      取消：用 bookingId
 *   RESCHEDULE  改期：用 bookingId + newCheckIn/newCheckOut
 * </pre>
 * 用一个类装三种动作、允许字段为空，而不是拆三个类 —— 因为它们的生命周期完全一致
 * （生成 → 等确认 → 用完作废），拆开只会让会话多三个字段和一堆 null 判断。
 *
 * <h3>为什么不带 userId</h3>
 * 身份在真正执行的那一刻从 {@code UserContext} 取。
 * 草案里放 userId 反而多一个可能被篡改的入口。
 *
 * <h3>为什么要有 createdAt</h3>
 * 草案是"某一刻的房态 + 价格"。放了 10 分钟再确认，价格可能变了、房可能没了。
 * 过期就让用户重新查一遍，比他确认后才发现贵了要好。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDraft {

    /** BOOK / CANCEL / RESCHEDULE */
    @Builder.Default
    private String kind = "BOOK";

    /** 取消、改期要用的订单号 */
    private Long bookingId;

    // ---------- 预订用（取消/改期时用于展示）----------
    private Long hotelId;
    private String hotelName;

    private Long roomTypeId;
    private String roomTypeName;

    /** 入住 / 离店：预订时是"要订的"，取消时是"订单原本的"，改期时是"原来的" */
    private LocalDate checkIn;
    private LocalDate checkOut;

    private Integer roomCount;

    /** 报价时算出来的总价（给人看，下单时由服务端重算） */
    private BigDecimal totalAmount;

    // ---------- 改期用 ----------
    private LocalDate newCheckIn;
    private LocalDate newCheckOut;

    /** 生成时间（epoch 毫秒），用来判断是否过期 */
    private long createdAt;
}
