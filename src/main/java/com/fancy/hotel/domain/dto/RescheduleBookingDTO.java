package com.fancy.hotel.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 改期请求
 *
 * <p>P0.1 只支持改日期，<b>间数不变</b>（改间数属于"改单"，语义和差价都更复杂，留给后续）。
 */
@Data
public class RescheduleBookingDTO {

    @NotNull(message = "不能为空")
    private LocalDate newCheckIn;

    @NotNull(message = "不能为空")
    private LocalDate newCheckOut;
}
