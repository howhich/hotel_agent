package com.fancy.hotel.controller;

import com.fancy.hotel.common.Result;
import com.fancy.hotel.service.HotelKbIndex;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按名称搜索酒店（P5.5）
 *
 * <pre>
 *   GET /booking/hotel/search?keyword=西部国宾馆&size=5
 *   -> [{hotelId, hotelName, score}, ...]
 * </pre>
 *
 * <h3>为什么单独一个控制器，而不是放进 HotelKbController</h3>
 * 知识库那些接口（重建索引、试检索）是<b>运维动作</b>，挂 `/booking/hotel/kb/**`
 * 下面由管理员拦截器拦住；而"按名字搜酒店"是<b>用户能力</b>，
 * 普通用户登录就该能用。路径分开，权限也就自然分开了：
 *
 * <pre>
 *   /booking/hotel/kb/**   管理员（重建索引、调试检索）
 *   /booking/hotel/search  登录即可（用户搜酒店）
 * </pre>
 *
 * <h3>它和订房流程里的名称匹配是同一套能力</h3>
 * 都走 {@code HotelKbIndex.searchHotels}（ES + fuzziness=AUTO，容错别字）。
 * 区别只是：这里<b>返回列表和分数</b>给用户/前端看；
 * 订房流程里只取第一条去收窄候选。
 */
@RestController
@RequestMapping("/booking/hotel")
@RequiredArgsConstructor
public class HotelSearchController {

    private final HotelKbIndex kbIndex;

    @GetMapping("/search")
    public Result search(@RequestParam String keyword,
                        @RequestParam(required = false, defaultValue = "5") Integer size) {
        List<HotelKbIndex.HotelNameHit> hotels =
                kbIndex.searchHotels(keyword, size == null ? 5 : size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyword", keyword);
        out.put("count", hotels.size());
        out.put("hotels", hotels);
        return Result.ok(out);
    }
}
