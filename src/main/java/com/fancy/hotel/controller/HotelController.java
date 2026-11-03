package com.fancy.hotel.controller;

import com.fancy.hotel.common.RedisKeyConstants;
import com.fancy.hotel.common.Result;
import com.fancy.hotel.domain.dto.NearbyQueryDTO;
import com.fancy.hotel.service.HotelGeoIndex;
import com.fancy.hotel.service.HotelQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 酒店查询 / GEO 索引运维
 */
@RestController
@RequestMapping("/booking/hotel")
@RequiredArgsConstructor
public class HotelController {

    private final HotelQueryService hotelQueryService;
    private final HotelGeoIndex hotelGeoIndex;

    /**
     * 附近酒店
     *
     * <pre>
     *   GET /booking/hotel/nearby?longitude=120.14&amp;latitude=30.2489&amp;radiusKm=3&amp;limit=20
     * </pre>
     *
     * <p>结果按距离升序。{@code indexedHotels} 为 0 时说明 GEO 索引还没建，
     * 此时 {@code hotels} 为空不代表"附近没有酒店"。
     */
    @GetMapping("/nearby")
    public Result nearby(@Valid NearbyQueryDTO q) {
        return Result.ok(hotelQueryService.nearby(q));
    }

    /** 从 DB 重建 GEO 索引（下架/新增酒店后跑一次；索引被淘汰后也靠它） */
    @PostMapping("/geo/rebuild")
    public Result rebuildGeo() {
        int n = hotelGeoIndex.rebuildFromDb();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexed", n);
        out.put("size", hotelGeoIndex.size());
        return Result.ok(out);
    }

    /** 看一眼索引状态 */
    @GetMapping("/geo/stat")
    public Result geoStat() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", RedisKeyConstants.hotelGeoKey());
        out.put("size", hotelGeoIndex.size());
        return Result.ok(out);
    }
}
