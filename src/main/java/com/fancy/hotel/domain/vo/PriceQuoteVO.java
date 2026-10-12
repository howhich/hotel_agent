package com.fancy.hotel.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 价格明细
 *
 * <p>强调「逐晚累加」：每晚价格可能不同（周末/节假日），
 * 先算均价再乘会有舍入误差。
 */
@Data
@Builder
public class PriceQuoteVO {

    private Long roomTypeId;
    private Integer nights;
    private Integer roomCount;

    /** 逐晚明细 */
    private List<NightPrice> nightPrices;

    /** 单间总价 = Σ 每晚价格 */
    private BigDecimal pricePerRoom;

    /** 总价 = 单间总价 × 间数 */
    private BigDecimal totalAmount;

    @Data
    @Builder
    public static class NightPrice {
        private LocalDate stayDate;
        private BigDecimal price;
        /** 是否周末（示例用，便于前端展示） */
        private Boolean weekend;
    }
}
