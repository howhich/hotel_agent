package com.fancy.hotel.service;

import com.fancy.hotel.common.RedisKeyConstants;
import com.fancy.hotel.domain.entity.RoomPrice;
import com.fancy.hotel.mapper.RoomPriceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 价格日历缓存（P3.4）
 *
 * <pre>
 *   key    hotel:price:{rt:2001}:2026-11   (Hash，一个房型一个月一个桶)
 *   field  2026-11-16
 *   value  400.00     —— 这天的价格
 *          "-"        —— 这天查过了，确实没有价格日历
 * </pre>
 *
 * <h3>为什么要缓存价格</h3>
 * {@code quotePrice} 是全项目最热的读路径：报价、下单、改期都要走它。
 * 之前每次都查一次 {@code room_price}，一天都省不下来。
 *
 * <h3>★ 为什么值里会存一个 "-"</h3>
 * 只存"有价格的天"的话，"这天没价格"和"这天没缓存"在 Redis 里长得一模一样，
 * 于是查一个没有价格日历的日期会<b>每次都回源查库</b>（缓存穿透）。
 * 存一个 "-" 明确表示"查过了、真的没有"。
 *
 * <h3>失效</h3>
 * 改价之后必须删掉那一天的缓存，否则用户看到的是旧价。
 * 见 {@code POST /booking/hotel/price/update}。
 *
 * <h3>已知的一处竞态（TTL 兜底）</h3>
 * 极端情况下会这样：
 * <ol>
 *   <li>请求 A 发现某天没缓存，去查库（读到旧价 400）</li>
 *   <li>改价接口把库改成 500，并删掉缓存</li>
 *   <li>请求 A 把刚读到的 400 写回缓存</li>
 * </ol>
 * 于是缓存里是旧价。彻底解决要加版本号或"延迟双删"，
 * 这里用<b>较短的 TTL</b>（默认 10 分钟）兜住 —— 最多错 10 分钟。
 * 对价格这种"改了要尽快生效、但错一会儿不出大事"的数据，这个取舍是合理的。
 */
@Slf4j
@Service
public class PriceCacheService {

    /** 值等于它，表示"这天查过、确实没有价格" */
    private static final String NO_PRICE = "-";

    private final StringRedisTemplate redis;
    private final RoomPriceMapper roomPriceMapper;
    private final long ttlSeconds;
    private final long jitterSeconds;

    public PriceCacheService(
            StringRedisTemplate redis,
            RoomPriceMapper roomPriceMapper,
            @Value("${hotel.price.cache-ttl-seconds:600}") long ttlSeconds,
            @Value("${hotel.price.cache-ttl-jitter-seconds:120}") long jitterSeconds) {
        this.redis = redis;
        this.roomPriceMapper = roomPriceMapper;
        this.ttlSeconds = ttlSeconds;
        this.jitterSeconds = jitterSeconds;
    }

    // =========================================================================
    // 读
    // =========================================================================

    /**
     * 取 {@code [from, to)} 每天的价格
     *
     * @return <b>每一天都会有 key</b>：值为 {@code null} 表示"这天没有价格日历"
     */
    public Map<LocalDate, BigDecimal> getOrLoad(Long roomTypeId, LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            days.add(d);
        }
        Map<LocalDate, BigDecimal> result = new HashMap<>(days.size());
        List<LocalDate> missing = new ArrayList<>();

        for (LocalDate d : days) {
            String v;
            try {
                v = (String) redis.opsForHash().get(key(roomTypeId, d), d.toString());
            } catch (Exception e) {
                log.warn("读价格缓存失败，走 DB: rt={}, date={}, err={}", roomTypeId, d, e.getMessage());
                v = null;
            }
            if (v == null) {
                missing.add(d);
            } else {
                result.put(d, NO_PRICE.equals(v) ? null : new BigDecimal(v));
            }
        }

        if (missing.isEmpty()) {
            return result;
        }

        // 有缺失：一次性查库把整个区间补上（一条 SQL，不是逐天查）
        Map<LocalDate, BigDecimal> fromDb = queryDb(roomTypeId, from, to);
        for (LocalDate d : days) {
            BigDecimal price = fromDb.get(d);           // null = 这天没有价格日历
            result.put(d, price);
            writeCache(roomTypeId, d, price == null ? NO_PRICE : price.toPlainString());
        }
        log.debug("价格缓存回源: rt={}, {}~{} 缺失 {} 天", roomTypeId, from, to, missing.size());
        return result;
    }

    private Map<LocalDate, BigDecimal> queryDb(Long roomTypeId, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> map = new HashMap<>();
        try {
            List<RoomPrice> rows = roomPriceMapper.selectRange(roomTypeId, from, to);
            for (RoomPrice p : rows) {
                map.put(p.getStayDate(), p.effectivePrice());
            }
        } catch (Exception e) {
            log.error("查价格日历失败: rt={}, {}~{}", roomTypeId, from, to, e);
        }
        return map;
    }

    // =========================================================================
    // 写 / 失效
    // =========================================================================

    private void writeCache(Long roomTypeId, LocalDate date, String value) {
        try {
            String k = key(roomTypeId, date);
            redis.opsForHash().put(k, date.toString(), value);
            redis.expire(k, ttlWithJitter());
        } catch (Exception e) {
            log.warn("写价格缓存失败（不影响业务）: rt={}, date={}, err={}", roomTypeId, date, e.getMessage());
        }
    }

    /**
     * 改价之后必须调它，否则读到的还是旧价
     *
     * @return 是否真的删掉了（false = 这天本来就没缓存）
     */
    public boolean invalidate(Long roomTypeId, LocalDate date) {
        try {
            Long n = redis.opsForHash().delete(key(roomTypeId, date), date.toString());
            if (n != null && n > 0) {
                log.info("价格缓存已失效: rt={}, date={}", roomTypeId, date);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("失效价格缓存失败（等 TTL 兜底）: rt={}, date={}, err={}",
                    roomTypeId, date, e.getMessage());
            return false;
        }
    }

    /** 整个房型某月全部失效（批量改价时用；当前没有批量接口，先留着） */
    public void invalidateMonth(Long roomTypeId, YearMonth ym) {
        try {
            redis.delete(RedisKeyConstants.priceMonthKey(roomTypeId, ym));
        } catch (Exception e) {
            log.warn("失效整月价格缓存失败: {}", e.getMessage());
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private static String key(Long roomTypeId, LocalDate date) {
        return RedisKeyConstants.priceMonthKey(roomTypeId, YearMonth.from(date));
    }

    /**
     * 带随机抖动的 TTL
     *
     * <p>和库存缓存同一个理由（P3.3）：一批 key 若同时创建、TTL 又固定，
     * 就会同时过期。这里也把过期时间摊开。
     */
    private Duration ttlWithJitter() {
        long base = Math.max(1, ttlSeconds);
        long jitter = Math.max(0, jitterSeconds);
        long extra = jitter == 0 ? 0 : ThreadLocalRandom.current().nextLong(jitter + 1);
        return Duration.ofSeconds(base + extra);
    }
}
