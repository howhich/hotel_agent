package com.fancy.hotel.domain.dto;

import lombok.Data;

/**
 * 支付请求
 */
@Data
public class PayBookingDTO {

    /** 支付渠道 1-支付宝 2-微信 3-银行卡 */
    private Integer payChannel = 1;

    /** 支付流水号 */
    private String tradeNo;
}
