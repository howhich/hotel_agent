package com.fancy.hotel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.hotel.common.BusinessException;
import com.fancy.hotel.domain.dto.NearbyQueryDTO;
import com.fancy.hotel.domain.entity.Hotel;
import com.fancy.hotel.domain.vo.NearbyResultVO;
import com.fancy.hotel.mapper.HotelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 酒店查询
 *
 * <p>P2.2 只做「GEO 半径内有哪些酒店 + 距离」。排序（评分/价格）和有房过滤是 P2.3。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotelQueryService {

    private final HotelGeoIndex hotelGeoIndex;
    private final HotelMapper hotelMapper;

    @Value("${hotel.nearby.default-radius-km:5}")
    private double defaultRadiusKm;

    @Value("${hotel.nearby.default-limit:20}")
    private int defaultLimit;

    /**
     * 附近酒店
     *
     * <p>两步：<b>GEO 拿酒店 ID + 距离</b>（快，不碰 DB）→ <b>一次 IN 查询补详情</b>。
     *
     * <p>★ 为什么不用"先查 DB 再算距离"：那要全表扫酒店再逐个算 haversine，
     * 而且没法用半径索引。GEO 的 ZSet 就是干这个的。
     *
     * <p>★ 也不能对每个命中的酒店单独查一次详情 —— 那是 N+1。
     */
    public NearbyResultVO nearby(NearbyQueryDTO q) {
        if (q.getLongitude() == null || q.getLatitude() == null) {
            throw BusinessException.badRequest("longitude 和 latitude 不能为空");
        }
        double radius = q.getRadiusKm() == null ? defaultRadiusKm : q.getRadiusKm();
        int limit = q.getLimit() == null ? defaultLimit : q.getLimit();

        List<HotelGeoIndex.GeoHit> hits = hotelGeoIndex.search(
                q.getLongitude(), q.getLatitude(), radius, limit);
        long indexed = hotelGeoIndex.size();

        if (hits.isEmpty()) {
            return NearbyResultVO.builder()
                    .longitude(q.getLongitude())
                    .latitude(q.getLatitude())
                    .radiusKm(radius)
                    .count(0)
                    .indexedHotels(indexed)
                    .hotels(List.of())
                    .build();
        }

        // 一次 IN 查询把详情捞回来
        List<Long> ids = hits.stream().map(HotelGeoIndex.GeoHit::hotelId).toList();
        List<Hotel> hotels = hotelMapper.selectList(new LambdaQueryWrapper<Hotel>()
                .in(Hotel::getHotelId, ids));
        Map<Long, Hotel> byId = new HashMap<>(hotels.size());
        for (Hotel h : hotels) {
            byId.put(h.getHotelId(), h);
        }

        // 按 GEO 返回的顺序装配 —— GEO 已经按距离升序，别在这里重排
        List<NearbyResultVO.NearbyHotelVO> list = new ArrayList<>(hits.size());
        for (HotelGeoIndex.GeoHit hit : hits) {
            Hotel h = byId.get(hit.hotelId());
            if (h == null) {
                // 索引里有过期成员（酒店下架/删除了）。不影响本次结果，靠 rebuild 清理
                log.debug("GEO 索引命中了 DB 里不存在的酒店: hotelId={}", hit.hotelId());
                continue;
            }
            list.add(NearbyResultVO.NearbyHotelVO.builder()
                    .hotelId(h.getHotelId())
                    .name(h.getName())
                    .city(h.getCity())
                    .address(h.getAddress())
                    .star(h.getStar())
                    .score(h.getScore())
                    .minPrice(h.getMinPrice())
                    .longitude(h.getLongitude())
                    .latitude(h.getLatitude())
                    .distanceKm(round3(hit.distanceKm()))
                    .build());
        }

        return NearbyResultVO.builder()
                .longitude(q.getLongitude())
                .latitude(q.getLatitude())
                .radiusKm(radius)
                .count(list.size())
                .indexedHotels(indexed)
                .hotels(list)
                .build();
    }

    /** 距离保留 3 位小数（米级），避免返回 1.3799999999 这种 */
    private static double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
