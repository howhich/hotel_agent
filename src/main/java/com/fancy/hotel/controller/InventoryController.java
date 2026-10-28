package com.fancy.hotel.controller;

import com.fancy.hotel.common.Result;
import com.fancy.hotel.service.InventoryCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 区间库存缓存的运维 / 对账接口
 *
 * <p>这些不是给用户用的业务接口，而是排查工具：
 * 缓存和 DB 是否一致、缓存里到底存了什么、漂移了怎么修。
 *
 * <p>★ 之所以需要它们，是因为 P1 引入了<b>第三份库存数据</b>：
 * <pre>
 *   Redis 预占（快） ── 派生
 *   room_inventory.sold_rooms（汇总） ── 派生
 *   booking_inventory_link 台账（事实） ── 可反过来重建上面两份
 * </pre>
 */
@RestController
@RequestMapping("/booking/inv")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryCacheService inventoryCacheService;

    /**
     * 用 DB 的值覆盖重建 Redis
     *
     * <p>⚠️ 会冲掉 Redis 里可能比 DB 更新的预占值。只在确认 DB 是准的时用。
     */
    @PostMapping("/rebuild")
    public Result rebuild(@RequestParam Long roomTypeId,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        int days = inventoryCacheService.rebuildRangeFromDb(roomTypeId, from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomTypeId", roomTypeId);
        out.put("from", from);
        out.put("to", to);
        out.put("writtenDays", days);
        return Result.ok(out);
    }

    /**
     * 对账：Redis 与 DB 逐天对比
     *
     * <p>{@code diffs} 为空 == 一致。注意 {@code notCachedDays} 是"缓存里还没有的天"，
     * <b>不算漂移</b>（懒加载，用到时会回源 DB）。
     */
    @GetMapping("/compare")
    public Result compare(@RequestParam Long roomTypeId,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        InventoryCacheService.CompareResult r = inventoryCacheService.compareWithDb(roomTypeId, from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomTypeId", roomTypeId);
        out.put("from", from);
        out.put("to", to);
        out.put("comparedDays", r.comparedDays());
        out.put("notCachedDays", r.notCachedDays());
        out.put("diffCount", r.diffs().size());
        out.put("consistent", r.consistent());
        out.put("diffs", r.diffs());
        return Result.ok(out);
    }

    /** 只读地看 Redis 里存了什么（value 为 null = 缓存里没有这天） */
    @GetMapping("/dump")
    public Result dump(@RequestParam Long roomTypeId,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(inventoryCacheService.dump(roomTypeId, from, to));
    }
}
