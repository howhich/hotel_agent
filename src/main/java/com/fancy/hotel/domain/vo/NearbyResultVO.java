package com.fancy.hotel.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 附近酒店查询结果
 */
@Data
@Builder
public class NearbyResultVO {

    /** 查询中心点 */
    private Double longitude;
    private Double latitude;

    /** 实际生效的半径（可能来自默认值） */
    private Double radiusKm;

    /** 命中几家 */
    private Integer count;

    /**
     * GEO 索引里一共有多少家酒店
     *
     * <p>★ 这个字段是给排查用的：如果它为 0，说明索引没建（被淘汰了/没预热），
     * 那 {@code hotels} 为空就<b>不是"附近没有酒店"</b>，而是"索引还没建"。
     * 调 {@code POST /booking/hotel/geo/rebuild} 即可。
     */
    private Long indexedHotels;

    private List<NearbyHotelVO> hotels;

    /**
     * 一家酒店 + 它离查询中心的直线距离
     */
    @Data
    @Builder
    public static class NearbyHotelVO {
        private Long hotelId;
        private String name;
        private String city;
        private String address;
        private Integer star;
        private BigDecimal score;
        private BigDecimal minPrice;
        private BigDecimal longitude;
        private BigDecimal latitude;
        /** 直线距离（公里），由 Redis GEO 计算 */
        private Double distanceKm;
    }
}
