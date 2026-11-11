package com.fancy.hotel.domain.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 待用户确认的预订草案（P4.4）
 *
 * <p>Agent 查到候选之后，把"要订什么"整份存进会话；用户说"确认"时
 * <b>用它下单，而不是用用户这句话里提到的东西</b>。
 *
 * <h3>★ 为什么必须以草案为准</h3>
 * 如果确认时去解析用户那句话（"订那个 100 块的"），模型就可能被引导去订另一单。
 * 草案是<b>服务端自己查出来、自己算过价</b>的那一份，它才可信。
 *
 * <h3>为什么不带 userId</h3>
 * 身份在真正下单的那一刻从 {@code UserContext} 取。
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

    private Long hotelId;
    private String hotelName;

    private Long roomTypeId;
    private String roomTypeName;

    private LocalDate checkIn;
    private LocalDate checkOut;

    private Integer roomCount;

    /** 报价时算出来的总价（给人看，下单时由服务端重算） */
    private BigDecimal totalAmount;

    /** 生成时间（epoch 毫秒），用来判断是否过期 */
    private long createdAt;
}
