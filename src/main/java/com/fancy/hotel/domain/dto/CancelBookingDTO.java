package com.fancy.hotel.domain.dto;

import lombok.Data;

/**
 * 取消请求
 */
@Data
public class CancelBookingDTO {

    private String reason;

    /** 取消方：1用户 2客服 3系统（P0 由请求体传入，接入鉴权后从角色推导） */
    private Integer cancelRole = 1;
}
