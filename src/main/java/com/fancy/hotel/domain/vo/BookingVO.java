package com.fancy.hotel.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预订单详情
 */
@Data
@Builder
public class BookingVO {

    private Long bookingId;
    private Long userId;

    private Long hotelId;
    private String hotelName;

    private Long roomTypeId;
    private String roomTypeName;

    // ===== 区间（左闭右开）=====
    private LocalDate checkIn;
    private LocalDate checkOut;
    private Integer nights;
    private Integer roomCount;
    private Integer guestCount;

    // ===== 金额 =====
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private BigDecimal refundAmount;

    // ===== 状态 =====
    private Integer bookingStatus;
    private String statusDesc;

    /** 取消政策码与描述 */
    private Integer cancelPolicy;
    private String cancelPolicyDesc;

    private String cancelRoleDesc;
    private String cancelReason;

    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime cancelTime;
}
