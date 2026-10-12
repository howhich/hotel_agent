package com.fancy.hotel.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 创建预订请求
 *
 * <p>日期用 ISO 格式字符串（{@code 2026-07-01}），Spring Boot 自带的
 * jackson-datatype-jsr310 会自动绑定到 {@link LocalDate}。
 *
 * <p>⚠️ P0 阶段先不接鉴权，{@code userId} 直接由请求体传入。
 * P4 接入 Agent / 鉴权后，会改成「从 token 或 ToolContext 取」，不再信任请求体。
 */
@Data
public class CreateBookingDTO {

    /** 下单用户（P0 临时字段） */
    @NotNull(message = "不能为空")
    private Long userId;

    @NotNull(message = "不能为空")
    private Long hotelId;

    @NotNull(message = "不能为空")
    private Long roomTypeId;

    /** 入住日（含） */
    @NotNull(message = "不能为空")
    private LocalDate checkIn;

    /** 离店日（不含） */
    @NotNull(message = "不能为空")
    private LocalDate checkOut;

    @Min(value = 1, message = "至少 1 间")
    private Integer roomCount = 1;

    @Min(value = 1, message = "至少 1 人")
    private Integer guestCount = 1;

    /** 是否含早 0/1 */
    private Integer withBreakfast = 0;
}
