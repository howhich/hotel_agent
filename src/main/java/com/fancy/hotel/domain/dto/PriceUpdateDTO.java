package com.fancy.hotel.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 改某一天的价格（P3.4）
 *
 * <p>这是运维/后台用的接口，加它的目的是<b>真正验证"改了数据，缓存要失效"</b> ——
 * 没有写接口的话，价格缓存加了也永远不需要失效，这个最重要的教训就演示不出来。
 */
@Data
public class PriceUpdateDTO {

    @NotNull(message = "roomTypeId 不能为空")
    private Long roomTypeId;

    @NotNull(message = "stayDate 不能为空")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate stayDate;

    @NotNull(message = "price 不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于 0")
    private BigDecimal price;
}
