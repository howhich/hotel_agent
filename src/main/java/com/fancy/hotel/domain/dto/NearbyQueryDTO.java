package com.fancy.hotel.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 附近酒店查询
 *
 * <p>GET 请求，字段直接从 query string 绑定（不加 {@code @RequestBody}）。
 * {@code radiusKm} / {@code limit} 不传时用配置里的默认值。
 */
@Data
public class NearbyQueryDTO {

    /** 中心点经度 */
    @DecimalMin(value = "-180", message = "经度范围是 -180~180")
    @DecimalMax(value = "180", message = "经度范围是 -180~180")
    private Double longitude;

    /** 中心点纬度 */
    @DecimalMin(value = "-90", message = "纬度范围是 -90~90")
    @DecimalMax(value = "90", message = "纬度范围是 -90~90")
    private Double latitude;

    /** 半径（公里），不传用默认值 */
    @DecimalMin(value = "0.01", message = "半径至少 0.01 公里")
    @DecimalMax(value = "100", message = "半径最大 100 公里")
    private Double radiusKm;

    /** 最多返回多少条（上限 50） */
    @DecimalMin(value = "1", message = "limit 至少 1")
    @DecimalMax(value = "50", message = "limit 最多 50")
    private Integer limit;

    // ===== P2.3：排序 + 有房过滤 =====

    /** 排序方式：distance（默认）/ rating / price */
    private String sortBy;

    /**
     * 入住日（含）
     *
     * <p>{@code checkIn} 和 {@code checkOut} <b>一起传</b>才会做有房过滤；
     * 只传一个会报 400（避免"半个条件"被静默忽略）。
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate checkIn;

    /** 离店日（不含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate checkOut;

    /** 间数，做有房过滤时用，默认 1 */
    @DecimalMin(value = "1", message = "间数至少 1")
    @DecimalMax(value = "5", message = "单次最多 5 间")
    private Integer roomCount;
}
