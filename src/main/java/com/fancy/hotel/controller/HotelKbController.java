package com.fancy.hotel.controller;

import com.fancy.hotel.common.Result;
import com.fancy.hotel.service.HotelKbIndex;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酒店知识库（P5.3）
 *
 * <pre>
 *   POST /booking/hotel/kb/rebuild   从 MySQL 全量重建 ES 索引（运维，要管理员）
 *   GET  /booking/hotel/kb/search    手工试检索（调试用，要管理员）
 * </pre>
 *
 * <p>索引是<b>可重建的派生数据</b>，所以"重建"是个正常的运维动作，
 * 不需要迁移脚本 —— 和库存缓存、GEO 索引一个思路。
 */
@RestController
@RequestMapping("/booking/hotel/kb")
@RequiredArgsConstructor
public class HotelKbController {

    private final HotelKbIndex kbIndex;

    @PostMapping("/rebuild")
    public Result rebuild() {
        int count = kbIndex.rebuild();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexed", count);
        out.put("total", kbIndex.count());
        return Result.ok(out);
    }

    @GetMapping("/search")
    public Result search(@RequestParam String q,
                         @RequestParam(required = false) Long hotelId,
                         @RequestParam(required = false, defaultValue = "3") Integer size) {
        List<HotelKbIndex.KbHit> hits = kbIndex.retrieve(q, hotelId, size == null ? 3 : size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hits", hits);
        out.put("matchedHotelId", kbIndex.matchHotelId(q));
        return Result.ok(out);
    }
}
