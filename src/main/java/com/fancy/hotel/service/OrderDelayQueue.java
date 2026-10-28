package com.fancy.hotel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.hotel.common.RedisKeyConstants;
import com.fancy.hotel.domain.entity.Booking;
import com.fancy.hotel.domain.enums.BookingStatus;
import com.fancy.hotel.mapper.BookingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单超时延迟队列（Redis ZSet）
 *
 * <pre>
 *   key    hotel:delay:order      (ZSet)
 *   score  触发时刻的 epoch 毫秒
 *   member bookingId
 * </pre>
 *
 * <p><b>定位：精确定时的主路径，但不是唯一保险。</b>
 * <ul>
 *   <li>它是"待办任务"，属于唯一事实来源那类数据 —— 整体丢了任务就没了</li>
 *   <li>所以还有一层 DB 扫表兜底（{@code OrderTimeoutJob.sweep()}）</li>
 *   <li>另外服务器 Redis 淘汰策略已改为 {@code volatile-lru}，
 *       本 key 不设 TTL 因此不会被淘汰</li>
 * </ul>
 *
 * <p><b>所有方法都吞异常</b>：延迟队列是"辅助设施"，它坏了不能让下单失败。
 * 最坏情况只是这一次没走精确触发，交给扫表兜底。
 */
@Slf4j
@Service
@SuppressWarnings("rawtypes")
public class OrderDelayQueue {

    private final StringRedisTemplate redis;
    private final RedisScript<List> claimScript;
    private final BookingMapper bookingMapper;
    private final int payTimeoutMinutes;
    private final int defaultBatchSize;

    public OrderDelayQueue(
            StringRedisTemplate redis,
            @Qualifier("delayClaimScript") RedisScript<List> claimScript,
            BookingMapper bookingMapper,
            @Value("${hotel.booking.pay-timeout-minutes:15}") int payTimeoutMinutes,
            @Value("${hotel.booking.delay.batch-size:50}") int defaultBatchSize) {
        this.redis = redis;
        this.claimScript = claimScript;
        this.bookingMapper = bookingMapper;
        this.payTimeoutMinutes = payTimeoutMinutes;
        this.defaultBatchSize = defaultBatchSize;
    }

    private static String key() {
        return RedisKeyConstants.orderDelayQueueKey();
    }

    // =========================================================================
    // 入队 / 出队
    // =========================================================================

    /**
     * 入队
     *
     * <p>★ 必须在<b>事务提交之后</b>调用（见 {@code BookingServiceImpl} 里的
     * {@code afterTransaction}）—— 否则回滚的订单会在队列里留下一条僵尸任务。
     */
    public boolean enqueue(long bookingId, long executeAtMillis) {
        try {
            Boolean ok = redis.opsForZSet().add(key(), String.valueOf(bookingId), executeAtMillis);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.error("延迟队列入队失败（不影响下单，靠扫表兜底）: bookingId={}, executeAt={}",
                    bookingId, executeAtMillis, e);
            return false;
        }
    }

    /** 下单场景的触发时刻：现在 + 支付超时 */
    public long executeAtFromNow() {
        return System.currentTimeMillis() + payTimeoutMinutes * 60_000L;
    }

    /** 由下单时间推算触发时刻（重建时用） */
    public long executeAt(LocalDateTime createTime) {
        return createTime.plusMinutes(payTimeoutMinutes)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 出队
     *
     * <p>支付成功 / 取消 / 失效之后调用，避免到点时白跑一趟。
     * 没出队也不会出错（{@code expireBooking} 有条件 UPDATE 兜着），只是浪费一次空转。
     */
    public boolean remove(long bookingId) {
        try {
            Long n = redis.opsForZSet().remove(key(), String.valueOf(bookingId));
            return n != null && n > 0;
        } catch (Exception e) {
            log.warn("延迟队列出队失败: bookingId={}, err={}", bookingId, e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // 认领
    // =========================================================================

    /**
     * 原子认领到期任务
     *
     * <p>「取 + 删」在一个 Lua 里完成，所以两个消费者不会拿到同一个成员 ——
     * 谁删掉谁处理。这是多实例安全的根本。
     *
     * @return 被认领的 bookingId；空列表 = 当前没有到期任务
     */
    public List<Long> claimDue(Integer limit) {
        int n = (limit == null || limit <= 0) ? defaultBatchSize : limit;
        try {
            List<String> res = redis.execute(claimScript, List.of(key()),
                    new Object[]{String.valueOf(System.currentTimeMillis()), String.valueOf(n)});
            if (res == null || res.isEmpty()) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>(res.size());
            for (String s : res) {
                try {
                    ids.add(Long.valueOf(s));
                } catch (NumberFormatException e) {
                    log.warn("延迟队列里有非法的 member，已忽略: {}", s);
                }
            }
            return ids;
        } catch (Exception e) {
            log.error("延迟队列认领失败（本轮跳过，下一轮再来）", e);
            return List.of();
        }
    }

    // =========================================================================
    // 运维
    // =========================================================================

    /**
     * 队列统计
     *
     * @param depth             队列里一共排着多少任务
     * @param due               已经到点、等待被认领的数量
     * @param oldestAgeSeconds  最老那条已经等了多久（null = 队列为空）
     */
    public record DelayStat(long depth, long due, Long oldestAgeSeconds) {
    }

    public DelayStat stat() {
        String k = key();
        long depth = nz(redis.opsForZSet().zCard(k));
        long due = nz(redis.opsForZSet().count(k, Double.NEGATIVE_INFINITY,
                (double) System.currentTimeMillis()));

        Long oldestAge = null;
        var head = redis.opsForZSet().rangeWithScores(k, 0, 0);
        if (head != null && !head.isEmpty()) {
            Double score = head.iterator().next().getScore();
            if (score != null) {
                oldestAge = Math.max(0L, (long) (System.currentTimeMillis() - score) / 1000L);
            }
        }
        return new DelayStat(depth, due, oldestAge);
    }

    /**
     * 从 DB 重建队列
     *
     * <p>把当前所有「待支付」订单按 {@code create_time + 超时} 重新算出触发时刻入队。
     * 用 {@code ZADD} 覆盖同 member 的 score，所以重复执行是安全的。
     *
     * @return 重新入队的数量
     */
    public int rebuildFromDb() {
        List<Booking> pending = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getBookingStatus, BookingStatus.PENDING_PAYMENT.getCode()));
        int n = 0;
        for (Booking b : pending) {
            if (b.getBookingId() == null || b.getCreateTime() == null) {
                continue;
            }
            if (enqueue(b.getBookingId(), executeAt(b.getCreateTime()))) {
                n++;
            }
        }
        log.info("延迟队列重建完成：{} 个待支付订单重新入队", n);
        return n;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
