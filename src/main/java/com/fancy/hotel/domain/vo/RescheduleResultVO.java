package com.fancy.hotel.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 改期结果
 *
 * <p>★ 这三个日期列表是本次改期的核心产出，也是台账价值的直接体现：
 * 只有**差集**被动过，重叠部分一根手指都没碰。
 */
@Data
@Builder
public class RescheduleResultVO {

    private Long bookingId;

    private LocalDate oldCheckIn;
    private LocalDate oldCheckOut;
    private Integer oldNights;

    private LocalDate newCheckIn;
    private LocalDate newCheckOut;
    private Integer newNights;

    private BigDecimal oldTotalAmount;
    private BigDecimal newTotalAmount;

    /** 新价 - 旧价：正数需补差价，负数应退差价 */
    private BigDecimal priceDiff;

    // ===== ★ 差集明细 =====

    /** 被释放的日期（旧有新无） */
    private List<LocalDate> releasedDates;

    /** 新占用的日期（新有旧无） */
    private List<LocalDate> occupiedDates;

    /** 一动没动的日期（新旧重叠）—— 这些房间从来没被释放过，也不存在被别人抢走的窗口 */
    private List<LocalDate> untouchedDates;

    private Integer bookingStatus;
    private String statusDesc;
}
