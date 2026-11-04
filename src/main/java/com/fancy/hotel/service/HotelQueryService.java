package com.fancy.hotel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.hotel.common.BusinessException;
import com.fancy.hotel.domain.dto.NearbyQueryDTO;
import com.fancy.hotel.domain.entity.Hotel;
import com.fancy.hotel.domain.entity.RoomInventory;
import com.fancy.hotel.domain.entity.RoomType;
import com.fancy.hotel.domain.enums.NearbySortBy;
import com.fancy.hotel.domain.vo.NearbyResultVO;
import com.fancy.hotel.mapper.HotelMapper;
import com.fancy.hotel.mapper.RoomInventoryMapper;
import com.fancy.hotel.mapper.RoomTypeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 酒店查询：附近酒店 + 排序 + 有房过滤
 *
 * <p>处理顺序：<b>GEO 取候选 → 有房过滤 → 排序 → 截断</b>。顺序不能乱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotelQueryService {

    /**
     * 非距离排序 / 有房过滤时，GEO 最多取这么多个候选
     *
     * <p>★ 为什么不能直接取 limit 个：如果"先截断到 10 家，再按价格排"，
     * 得到的是"最近 10 家里最便宜的"，而不是"范围内最便宜的 10 家"。
     * 有房过滤同理 —— 候选被过滤掉一部分后就不够 limit 了。
     */
    private static final int MAX_CANDIDATES = 200;

    private static final BigDecimal HIGH_PRICE = new BigDecimal("999999");

    private final HotelGeoIndex hotelGeoIndex;
    private final HotelMapper hotelMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final RoomInventoryMapper roomInventoryMapper;

    @Value("${hotel.nearby.default-radius-km:5}")
    private double defaultRadiusKm;

    @Value("${hotel.nearby.default-limit:20}")
    private int defaultLimit;

    /**
     * 附近酒店
     *
     * @throws BusinessException 400 —— 经纬度缺失、排序值非法、只传了半个日期区间
     */
    public NearbyResultVO nearby(NearbyQueryDTO q) {
        if (q.getLongitude() == null || q.getLatitude() == null) {
            throw BusinessException.badRequest("longitude 和 latitude 不能为空");
        }
        double radius = q.getRadiusKm() == null ? defaultRadiusKm : q.getRadiusKm();
        int limit = q.getLimit() == null ? defaultLimit : q.getLimit();
        NearbySortBy sortBy = NearbySortBy.parse(q.getSortBy());

        // ---------- 有房过滤的入参校验：两个日期要么都给，要么都不给 ----------
        boolean hasIn = q.getCheckIn() != null;
        boolean hasOut = q.getCheckOut() != null;
        if (hasIn != hasOut) {
            throw BusinessException.badRequest(
                    "checkIn 和 checkOut 要一起传：都传 = 做有房过滤，都不传 = 只按距离/评分/价格排");
        }
        boolean filterAvailability = hasIn;
        if (filterAvailability && !q.getCheckIn().isBefore(q.getCheckOut())) {
            throw BusinessException.badRequest("离店日期必须晚于入住日期（区间左闭右开）");
        }
        int roomCount = q.getRoomCount() == null ? 1 : q.getRoomCount();

        // ---------- 候选数量 ----------
        boolean wide = filterAvailability || sortBy != NearbySortBy.DISTANCE;
        int candidateLimit = wide
                ? Math.min(MAX_CANDIDATES, Math.max(limit * 5, 50))
                : limit;

        List<HotelGeoIndex.GeoHit> hits = hotelGeoIndex.search(
                q.getLongitude(), q.getLatitude(), radius, candidateLimit);
        long indexed = hotelGeoIndex.size();

        List<NearbyResultVO.NearbyHotelVO> list = assemble(hits);

        // ---------- 有房过滤（批量，一次算完，不是每个酒店查一次）----------
        if (filterAvailability) {
            List<Long> ids = list.stream().map(NearbyResultVO.NearbyHotelVO::getHotelId).toList();
            Set<Long> bookable = bookableHotels(ids, q.getCheckIn(), q.getCheckOut(), roomCount);
            list.removeIf(v -> !bookable.contains(v.getHotelId()));
        }

        // ---------- 排序 ----------
        sortHotels(list, sortBy);

        int matchedTotal = list.size();
        if (list.size() > limit) {
            list = new ArrayList<>(list.subList(0, limit));
        }

        return NearbyResultVO.builder()
                .longitude(q.getLongitude())
                .latitude(q.getLatitude())
                .radiusKm(radius)
                .sortBy(sortBy.getCode())
                .availabilityFiltered(filterAvailability)
                .matchedTotal(matchedTotal)
                .count(list.size())
                .indexedHotels(indexed)
                .hotels(list)
                .build();
    }

    // =========================================================================
    // GEO 命中 → 酒店详情
    // =========================================================================

    /** 按 GEO 返回的顺序装配详情（GEO 已经按距离升序，这里不重排） */
    private List<NearbyResultVO.NearbyHotelVO> assemble(List<HotelGeoIndex.GeoHit> hits) {
        List<NearbyResultVO.NearbyHotelVO> list = new ArrayList<>(hits.size());
        if (hits.isEmpty()) {
            return list;
        }
        List<Long> ids = hits.stream().map(HotelGeoIndex.GeoHit::hotelId).toList();
        List<Hotel> hotels = hotelMapper.selectList(new LambdaQueryWrapper<Hotel>()
                .in(Hotel::getHotelId, ids));
        Map<Long, Hotel> byId = new HashMap<>(hotels.size());
        for (Hotel h : hotels) {
            byId.put(h.getHotelId(), h);
        }
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
        return list;
    }

    // =========================================================================
    // 有房过滤
    // =========================================================================

    /**
     * 一次算出"这些酒店里哪些在区间内至少有一个房型可订"
     *
     * <p>只跑 <b>2 条 SQL</b>，不随酒店数量增长：
     * <ol>
     *   <li>取出所有候选房型</li>
     *   <li>取出"区间内某天已售 + 要订 &gt; 总房量"的房型（= 被占满的）</li>
     * </ol>
     * 剩下的房型就是可订的；只要有<b>一个</b>可订房型，这家酒店就算可订。
     *
     * <p>注意：区间内没有库存行的日期，等于"还没人订过"（sold=0），
     * 不该被当成不可订 —— 所以用 JOIN 列出"被占满的"再排除，而不是用 JOIN 找"可订的"。
     */
    private Set<Long> bookableHotels(List<Long> hotelIds, LocalDate checkIn, LocalDate checkOut, int roomCount) {
        if (hotelIds.isEmpty()) {
            return Set.of();
        }

        List<RoomType> types = roomTypeMapper.selectList(new LambdaQueryWrapper<RoomType>()
                .in(RoomType::getHotelId, hotelIds)
                .eq(RoomType::getStatus, 1));
        if (types.isEmpty()) {
            return Set.of();
        }

        List<Long> typeIds = types.stream().map(RoomType::getRoomTypeId).toList();
        List<RoomInventory> blockedRows = roomInventoryMapper.selectList(
                new LambdaQueryWrapper<RoomInventory>()
                        .select(RoomInventory::getRoomTypeId)
                        .in(RoomInventory::getRoomTypeId, typeIds)
                        .ge(RoomInventory::getStayDate, checkIn)
                        .lt(RoomInventory::getStayDate, checkOut)
                        // {0} 会被替换成 #{} 绑定，不是字符串拼接
                        .apply("sold_rooms + {0} > total_rooms", roomCount));

        Set<Long> blockedTypes = blockedRows.stream()
                .map(RoomInventory::getRoomTypeId)
                .collect(Collectors.toSet());

        Set<Long> bookableHotels = new HashSet<>();
        for (RoomType t : types) {
            if (t.getTotalRooms() == null || t.getTotalRooms() < roomCount) {
                continue;   // 总房量本身都装不下，区间再空也订不了
            }
            if (blockedTypes.contains(t.getRoomTypeId())) {
                continue;   // 区间内被占满
            }
            bookableHotels.add(t.getHotelId());
        }

        log.debug("有房过滤: 候选酒店 {} 家 / 房型 {} 个 / 被占满 {} 个 / 可订酒店 {} 家",
                hotelIds.size(), types.size(), blockedTypes.size(), bookableHotels.size());
        return bookableHotels;
    }

    // =========================================================================
    // 排序
    // =========================================================================

    private void sortHotels(List<NearbyResultVO.NearbyHotelVO> list, NearbySortBy sortBy) {
        Comparator<NearbyResultVO.NearbyHotelVO> cmp;
        switch (sortBy) {
            case RATING -> cmp = Comparator.comparing(
                            (NearbyResultVO.NearbyHotelVO v) -> v.getScore() == null ? BigDecimal.ZERO : v.getScore())
                    .reversed();
            case PRICE -> cmp = Comparator.comparing(
                    (NearbyResultVO.NearbyHotelVO v) -> v.getMinPrice() == null ? HIGH_PRICE : v.getMinPrice());
            default -> cmp = Comparator.comparing(NearbyResultVO.NearbyHotelVO::getDistanceKm);
        }
        // 次级排序用距离：同评分 / 同价格时结果稳定且可预期
        list.sort(cmp.thenComparing(NearbyResultVO.NearbyHotelVO::getDistanceKm));
    }

    /** 距离保留 3 位小数（米级），避免返回 1.3799999999 这种 */
    private static double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
