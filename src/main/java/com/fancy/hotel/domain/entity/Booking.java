package com.fancy.hotel.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预订单
 *
 * <p>★ 入住区间左闭右开：占用 {@code [checkIn, checkOut)}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("booking")
public class Booking {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bookingId;
    private Long userId;
    private Long hotelId;
    private Long roomTypeId;

    // ===== 入住区间（左闭右开）=====

    /** 入住日（含） */
    private LocalDate checkIn;

    /** 离店日（不含） */
    private LocalDate checkOut;

    /** 晚数 = checkOut - checkIn */
    private Integer nights;

    private Integer roomCount;
    private Integer guestCount;
    private Integer withBreakfast;

    // ===== 金额 =====

    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private BigDecimal refundAmount;

    // ===== 状态 =====

    /** 10待支付 20已确认 30已入住 40已完成 90已取消 91已失效 */
    private Integer bookingStatus;

    /** 取消方：1用户 2客服 3系统 */
    private Integer cancelRole;

    private String cancelReason;

    /** 下单时的取消政策快照（JSON）—— 政策改了也不追溯老订单 */
    private String cancelPolicySnapshot;

    // ===== 时间轴 =====

    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
    private LocalDateTime cancelTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
