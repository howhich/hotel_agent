package com.fancy.hotel.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 取消结果
 */
@Data
@Builder
public class CancelResultVO {

    private Long bookingId;
    private Integer bookingStatus;
    private String statusDesc;

    private BigDecimal paidAmount;
    private BigDecimal refundAmount;

    /** 可退比例文案，如 "80%" */
    private String refundRatioText;

    /** 取消政策名（取自下单时的快照） */
    private String policyName;

    private String reason;
}
