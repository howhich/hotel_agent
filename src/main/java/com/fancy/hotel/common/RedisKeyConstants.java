package com.fancy.hotel.common;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis key 集中管理
 *
 * <p>key 名散落在各处是维护灾难（改名要全项目搜），所以统一放这里。
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {
    }

    private static final String INVENTORY_PREFIX = "hotel:inv";
    private static final String IDEMPOTENT_PREFIX = "hotel:idem";

    /**
     * 区间库存 bucket：<b>一个房型一个月</b>一个 hash
     *
     * <pre>
     *   key   hotel:inv:{rt:2001}:2026-10      (Hash)
     *   field 2026-10-12                       (日期)
     *   value 1                                (该天已售间数)
     * </pre>
     *
     * <p>★ key 里的 <code>{rt:2001}</code> 是 Redis Cluster 的 <b>hash tag</b>。
     * 它的作用是保证<b>同一房型的所有月份</b>落在同一个 slot —— 这样"跨月的日期区间"
     * 才能在<b>一次 Lua 调用</b>里操作多个 key（集群模式下 Lua 不允许跨 slot 访问）。
     *
     * <p>为什么按"月"而不是按"房型"一个 hash：房型的 hash 会无限增长
     * （一年 365 个 field × 常年累月），按月的 bucket 可以自然过期淘汰。
     */
    public static String inventoryMonthKey(Long roomTypeId, YearMonth month) {
        return INVENTORY_PREFIX + ":{rt:" + roomTypeId + "}:" + month;
    }

    public static String inventoryMonthKey(Long roomTypeId, LocalDate date) {
        return inventoryMonthKey(roomTypeId, YearMonth.from(date));
    }

    /**
     * 按月份给日期分桶，保持插入顺序
     *
     * <p>Lua 脚本的 KEYS 需要按 bucket 去重后的列表，ARGV 里再用下标引用，
     * 所以这里返回有序 Map。
     */
    public static Map<YearMonth, List<LocalDate>> bucketByMonth(Collection<LocalDate> dates) {
        Map<YearMonth, List<LocalDate>> buckets = new LinkedHashMap<>();
        for (LocalDate d : dates) {
            buckets.computeIfAbsent(YearMonth.from(d), k -> new ArrayList<>()).add(d);
        }
        return buckets;
    }

    /**
     * 幂等键
     *
     * <p>指纹由「业务类型 + 用户 + 关键业务参数」拼成，见 {@code IdempotentAspect}。
     */
    public static String idempotentKey(String bizType, String fingerprint) {
        return IDEMPOTENT_PREFIX + ":" + bizType + ":" + fingerprint;
    }
}
