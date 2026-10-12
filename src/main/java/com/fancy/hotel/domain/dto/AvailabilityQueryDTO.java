package com.fancy.hotel.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 可订查询请求（只读，不占库存）
 */
@Data
public class AvailabilityQueryDTO {

    @NotNull(message = "不能为空")
    private Long roomTypeId;

    @NotNull(message = "不能为空")
    private java.time.LocalDate checkIn;

    @NotNull(message = "不能为空")
    private java.time.LocalDate checkOut;

    private Integer roomCount = 1;
}
