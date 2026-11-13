package com.fancy.hotel.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.hotel.domain.dto.AvailabilityQueryDTO;
import com.fancy.hotel.domain.entity.Hotel;
import com.fancy.hotel.domain.entity.RoomType;
import com.fancy.hotel.domain.vo.AgentReplyVO;
import com.fancy.hotel.domain.vo.AvailabilityVO;
import com.fancy.hotel.domain.vo.PriceQuoteVO;
import com.fancy.hotel.mapper.HotelMapper;
import com.fancy.hotel.mapper.RoomTypeMapper;
import com.fancy.hotel.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 的"查可订 + 报价"工具（P4.3）
 *
 * <p>Agent 本身不做业务计算 —— 它只是把用户的话翻译成参数，然后调用<b>已有的服务</b>。
 * 这一点很重要：库存是否够、价格怎么算，答案只有一个来源（既有代码），
 * 不能让 Agent 另算一套。
 *
 * <h3>为什么按城市查而不是用 GEO</h3>
 * 用户说的是"杭州"，没有经纬度。GEO 的 {@code nearby} 需要坐标，
 * 而 {@code hotel.city} 是现成的。真要做"附近"，需要先把城市名转成坐标，
 * 那是另一个话题（P2.2 的 GEO 留给有坐标的场景用）。
 *
 * <h3>单个房型失败不能拖垮整次搜索</h3>
 * 某个房型没有价格日历（抛 409）或者查库出错，都只是"这个候选不行"，
 * 继续看下一个 —— 所以每个候选都单独 try/catch。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotelSearchTool {

    private final HotelMapper hotelMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final BookingService bookingService;
    private final com.fancy.hotel.service.HotelKbIndex kbIndex;

    /**
     * 按条件搜索可订的房型，按总价升序
     *
     * @param bedType 可以传 null 表示不限；传"大床"/"双床"会按房型名过滤
     */
    public List<AgentReplyVO.Candidate> search(String city, LocalDate checkIn, LocalDate checkOut,
                                               int roomCount, String bedType) {
        return search(city, null, checkIn, checkOut, roomCount, bedType);
    }

    /**
     * 带酒店名的搜索（P5.3）
     *
     * <p>原来的问题是：用户说"我要订杭州西湖国宾馆"，酒店名<b>完全没有参与检索</b> ——
     * 只能按城市查，于是把杭州所有酒店都列出来。
     *
     * <p>现在用 ES 做名称匹配（支持错别字，如"西部国宾馆"→"杭州西湖国宾馆"）。
     * 认不出来就<b>不收窄</b>，宁可多列几家，也不要因为认错而把正确答案筛掉。
     */
    public List<AgentReplyVO.Candidate> search(String city, String hotelNameHint,
                                               LocalDate checkIn, LocalDate checkOut,
                                               int roomCount, String bedType) {
        List<Hotel> hotels = findHotelsByCity(city);
        if (hotelNameHint != null && !hotelNameHint.isBlank() && !hotels.isEmpty()) {
            Long matched = kbIndex.matchHotelId(hotelNameHint);
            if (matched != null) {
                List<Hotel> narrowed = new ArrayList<>();
                for (Hotel h : hotels) {
                    if (matched.equals(h.getHotelId())) {
                        narrowed.add(h);
                    }
                }
                if (!narrowed.isEmpty()) {
                    log.debug("按酒店名收窄: 提示[{}] -> hotelId={}", hotelNameHint, matched);
                    hotels = narrowed;
                }
            } else {
                log.debug("酒店名没认出来，不做收窄: {}", hotelNameHint);
            }
        }
        if (hotels.isEmpty()) {
            return List.of();
        }

        List<Long> hotelIds = new ArrayList<>();
        Map<Long, Hotel> hotelById = new HashMap<>();
        for (Hotel h : hotels) {
            hotelIds.add(h.getHotelId());
            hotelById.put(h.getHotelId(), h);
        }

        List<RoomType> types = roomTypeMapper.selectList(new LambdaQueryWrapper<RoomType>()
                .in(RoomType::getHotelId, hotelIds)
                .eq(RoomType::getStatus, 1));

        List<AgentReplyVO.Candidate> out = new ArrayList<>();
        for (RoomType rt : types) {
            if (bedType != null && !bedType.isBlank()
                    && (rt.getName() == null || !rt.getName().contains(bedType))) {
                continue;   // 床型对不上
            }
            if (rt.getTotalRooms() == null || rt.getTotalRooms() < roomCount) {
                continue;   // 总房量都装不下
            }
            try {
                AvailabilityQueryDTO q = new AvailabilityQueryDTO();
                q.setRoomTypeId(rt.getRoomTypeId());
                q.setCheckIn(checkIn);
                q.setCheckOut(checkOut);
                q.setRoomCount(roomCount);

                AvailabilityVO av = bookingService.checkAvailability(q);
                if (av == null || !Boolean.TRUE.equals(av.getAvailable())) {
                    continue;   // 区间内有某天不够
                }

                PriceQuoteVO quote = bookingService.quotePrice(rt.getRoomTypeId(), checkIn, checkOut, roomCount);
                Hotel h = hotelById.get(rt.getHotelId());
                out.add(AgentReplyVO.Candidate.builder()
                        .hotelId(rt.getHotelId())
                        .hotelName(h == null ? null : h.getName())
                        .roomTypeId(rt.getRoomTypeId())
                        .roomTypeName(rt.getName())
                        .totalAmount(quote.getTotalAmount())
                        .nights(quote.getNights())
                        .roomCount(quote.getRoomCount())
                        .build());
            } catch (Exception e) {
                // 这个房型不行（没价格日历 / 数据有问题），跳过就好
                log.debug("候选房型 {} 跳过: {}", rt.getRoomTypeId(), e.getMessage());
            }
        }

        out.sort(Comparator.comparing(AgentReplyVO.Candidate::getTotalAmount));
        return out;
    }

    private List<Hotel> findHotelsByCity(String city) {
        if (city == null || city.isBlank()) {
            return List.of();
        }
        return hotelMapper.selectList(new LambdaQueryWrapper<Hotel>()
                .eq(Hotel::getCity, city)
                .eq(Hotel::getStatus, 1));
    }
}
