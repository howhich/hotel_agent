package com.fancy.hotel.service;

import com.fancy.hotel.common.RedisKeyConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 「这个东西不存在」的短期记忆 —— 防缓存穿透
 *
 * <h3>要解决的场景</h3>
 * <pre>
 *   GET /booking/123456789012345678   ← 随手编一个订单号
 *   → 查库 → 没有 → 404
 *   再刷 1000 次 → 查库 1000 次
 * </pre>
 * 数据库每次都白查一遍，因为它没有"没找到"的记忆。
 * 按 id 点查的接口（订单详情、房型校验、酒店查询）都有这个问题。
 *
 * <h3>做法</h3>
 * 查证"不存在"之后，把它记到 Redis 里一小段时间（默认 60 秒）。
 * 这段时间里同样的 id 直接返回 404，<b>一次 DB 都不碰</b>。
 *
 * <h3>为什么只记"不存在"，不缓存正本</h3>
 * <ul>
 *   <li>缓存正本就要处理"数据改了缓存什么时候失效" —— 多一类 bug</li>
 *   <li>写路径（下单、改期）会读到可能过期的 {@code total_rooms} —— 危险</li>
 *   <li>只记"不存在"这两条风险都不存在：命中正本永远走 DB，一定是最新的</li>
 * </ul>
 *
 * <h3>为什么 TTL 要短</h3>
 * "不存在"这个判断有可能过时（后台补录数据、数据修错了再改回来）。
 * TTL 短就把"记错了"的代价限制在 60 秒内 —— 到点自动恢复，不需要人工清缓存。
 * <b>这是这个方案唯一的取舍点，也是它安全的原因。</b>
 *
 * <h3>所有方法都吞异常</h3>
 * 它只是省一次数据库查询的优化。Redis 挂了就退化成"每次都查库"，
 * 绝不能因为它坏了让接口报错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbsentCache {

    private final StringRedisTemplate redis;

    @Value("${hotel.cache.absent-ttl-seconds:60}")
    private int absentTtlSeconds;

    /** 这个 id 是否已经被记成"不存在" */
    public boolean isAbsent(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (Exception e) {
            log.warn("查空值标记失败（当作没标记，照常查库）: key={}, err={}", key, e.getMessage());
            return false;
        }
    }

    /** 记下"这个 id 不存在" */
    public void markAbsent(String key) {
        if (absentTtlSeconds <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(key, "1", Duration.ofSeconds(absentTtlSeconds));
            log.debug("记下不存在的 id: {}（{} 秒内不再查库）", key, absentTtlSeconds);
        } catch (Exception e) {
            log.warn("写空值标记失败（不影响业务）: key={}, err={}", key, e.getMessage());
        }
    }

    /**
     * 主动忘掉这个标记
     *
     * <p>正常情况下不需要调 —— 靠 TTL 自然过期就够了。
     * 但如果某条数据是「刚查完不存在 → 立刻又被创建」的，主动清一下能马上恢复。
     */
    public void forget(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("清空值标记失败: key={}, err={}", key, e.getMessage());
        }
    }

    // =========================================================================
    // 三个便捷入口（把 key 的拼法收在一处，避免调用方各写一份）
    // =========================================================================

    public boolean isBookingAbsent(long bookingId) {
        return isAbsent(RedisKeyConstants.absentBookingKey(bookingId));
    }

    public void markBookingAbsent(long bookingId) {
        markAbsent(RedisKeyConstants.absentBookingKey(bookingId));
    }

    public boolean isRoomTypeAbsent(long roomTypeId) {
        return isAbsent(RedisKeyConstants.absentRoomTypeKey(roomTypeId));
    }

    public void markRoomTypeAbsent(long roomTypeId) {
        markAbsent(RedisKeyConstants.absentRoomTypeKey(roomTypeId));
    }

    public boolean isHotelAbsent(long hotelId) {
        return isAbsent(RedisKeyConstants.absentHotelKey(hotelId));
    }

    public void markHotelAbsent(long hotelId) {
        markAbsent(RedisKeyConstants.absentHotelKey(hotelId));
    }
}
