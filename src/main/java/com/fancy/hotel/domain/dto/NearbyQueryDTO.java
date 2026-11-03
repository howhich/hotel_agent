package com.fancy.hotel.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

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
}
