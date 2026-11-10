package com.fancy.hotel.service;

import com.fancy.hotel.common.RedisKeyConstants;
import com.fancy.hotel.domain.entity.RoomInventory;
import com.fancy.hotel.mapper.RoomInventoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区间库存的 Redis 缓存层
 *
 * <p><b>定位：它只是加速，不是裁决者。</b>
 * <ul>
 *   <li>它挡在前面，把绝大多数"明显不够"的请求在进 DB 之前就拒掉（快）</li>
 *   <li>真正决定成不成交的，永远是 DB 那条
 *       {@code UPDATE ... WHERE sold_rooms + n <= total_rooms}（准）</li>
 *   <li>所以缓存缺失、被淘汰、甚至和 DB 漂移，都<b>不影响正确性</b>——
 *       最坏情况只是退化成纯 DB 路径</li>
 * </ul>
 *
 * <p>服务器上 Redis 配的是 {@code allkeys-lru}，会淘汰任何键（包括没设 TTL 的）。
 * 对本项目<b>无害</b>：这里放的全是可从 DB 重建的派生数据。
 */
@Slf4j
@Service
@SuppressWarnings("rawtypes")
public class InventoryCacheService {

    private final StringRedisTemplate redis;
    private final RoomInventoryMapper roomInventoryMapper;
    private final RedisScript<List> reserveScript;
    private final RedisScript<List> releaseScript;
    private final DistributedLock lock;
    private final long cacheTtlSeconds;
    private final long cacheTtlJitterSeconds;
    private final long loadLockTtlSeconds;
    private final int loadWaitRounds;
    private final long loadWaitIntervalMs;

    public InventoryCacheService(
            StringRedisTemplate redis,
            RoomInventoryMapper roomInventoryMapper,
            @Qualifier("inventoryReserveScript") RedisScript<List> reserveScript,
            @Qualifier("inventoryReleaseScript") RedisScript<List> releaseScript,
            DistributedLock lock,
            @Value("${hotel.booking.inventory-cache-ttl-seconds:86400}") long cacheTtlSeconds,
            @Value("${hotel.booking.inventory-cache-ttl-jitter-seconds:3600}") long cacheTtlJitterSeconds,
            @Value("${hotel.lock.inv-load-ttl-seconds:5}") long loadLockTtlSeconds,
            @Value("${hotel.lock.inv-load-wait-rounds:3}") int loadWaitRounds,
            @Value("${hotel.lock.inv-load-wait-interval-ms:50}") long loadWaitIntervalMs) {
        this.redis = redis;
        this.roomInventoryMapper = roomInventoryMapper;
        this.reserveScript = reserveScript;
        this.releaseScript = releaseScript;
        this.lock = lock;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.cacheTtlJitterSeconds = cacheTtlJitterSeconds;
        this.loadLockTtlSeconds = loadLockTtlSeconds;
        this.loadWaitRounds = loadWaitRounds;
        this.loadWaitIntervalMs = loadWaitIntervalMs;
    }

    // =========================================================================
    // 结果类型
    // =========================================================================

    public enum ReserveStatus {
        /** 区间内每天都够，Redis 侧已预占 */
        OK,
        /** 某天剩余不足 */
        REJECTED,
        /** 缓存不可用 —— 调用方应退化为纯 DB 路径，而不是当成失败 */
        CACHE_MISS
    }

    public record ReserveResult(ReserveStatus status, LocalDate rejectedDate) {
        public static ReserveResult ok() {
            return new ReserveResult(ReserveStatus.OK, null);
        }

        public static ReserveResult rejected(LocalDate d) {
            return new ReserveResult(ReserveStatus.REJECTED, d);
        }

        public static ReserveResult miss() {
            return new ReserveResult(ReserveStatus.CACHE_MISS, null);
        }
    }

    public enum ReleaseStatus {
        /** 缓存里的值已扣减 */
        OK,
        /** 缓存里没有这天 —— 正常情况（被淘汰/没预热），不用管 */
        NOT_CACHED,
        /** 要释放的比已售还多 —— 缓存与 DB 漂移了，已归零并告警 */
        ANOMALY
    }

    /** Redis 与 DB 的库存差异（某天） */
    public record InvDiff(LocalDate stayDate, Integer cachedSold, int dbSold) {
    }

    /**
     * 对账结果
     *
     * @param comparedDays  对比了多少天
     * @param notCachedDays 缓存里没有的天数 —— <b>这不是漂移</b>：
     *                      下次用到会自动从 DB 回源，取到的就是 DB 的值
     * @param diffs         真正的漂移：两边都有值但不一致
     */
    public record CompareResult(int comparedDays, int notCachedDays, List<InvDiff> diffs) {
        public boolean consistent() {
            return diffs.isEmpty();
        }
    }

    // =========================================================================
    // 预检预占
    // =========================================================================

    /**
     * 对 {@code [区间内每一天]} 做原子预检 + 预占
     *
     * <p>脚本内部<b>先全查、再全扣</b>：任何一天不够就一天都不扣。
     *
     * <p>缓存缺失时会回源 DB 补一次并重试；再缺就返回 {@code CACHE_MISS}，
     * 由调用方走纯 DB 路径 —— <b>绝不因为缓存问题让用户下单失败</b>。
     */
    public ReserveResult tryReserve(Long roomTypeId, Integer totalRooms, List<LocalDate> dates, int n) {
        if (roomTypeId == null || dates == null || dates.isEmpty() || n <= 0) {
            return ReserveResult.miss();
        }
        if (totalRooms == null || totalRooms <= 0) {
            return ReserveResult.miss();
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            List<String> keys = new ArrayList<>();
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(n));
            args.add(String.valueOf(totalRooms));

            // KEYS: 去重后的月份 bucket；ARGV: (keyIndex, date) 交替
            Map<YearMonth, List<LocalDate>> buckets = RedisKeyConstants.bucketByMonth(dates);
            Map<YearMonth, Integer> keyIndex = new HashMap<>();
            for (YearMonth ym : buckets.keySet()) {
                keys.add(RedisKeyConstants.inventoryMonthKey(roomTypeId, ym));
                keyIndex.put(ym, keys.size());   // Lua 的 KEYS 下标是 1-based
            }
            for (Map.Entry<YearMonth, List<LocalDate>> e : buckets.entrySet()) {
                for (LocalDate d : e.getValue()) {
                    args.add(String.valueOf(keyIndex.get(e.getKey())));
                    args.add(d.toString());
                }
            }

            List<String> res;
            try {
                res = redis.execute(reserveScript, keys, args.toArray());
            } catch (Exception e) {
                log.warn("库存预占脚本执行失败，退化为纯 DB 路径: roomTypeId={}, err={}", roomTypeId, e.getMessage());
                return ReserveResult.miss();
            }

            String status = (res == null || res.isEmpty()) ? "MISS" : res.get(0);
            switch (status) {
                case "OK":
                    return ReserveResult.ok();
                case "REJECT":
                    return ReserveResult.rejected(res.size() > 1 ? LocalDate.parse(res.get(1)) : null);
                case "MISS":
                    // 回源补齐整个区间（HSETNX，不动缓存里已有的值），然后重试一次
                    LocalDate from = Collections.min(dates);
                    LocalDate to = Collections.max(dates).plusDays(1);
                    if (attempt == 0) {
                        // ★ single-flight：同一批并发请求只放一个去查库，其它的等它填好
                        loadWithSingleFlight(roomTypeId, from, to);
                    } else {
                        // 等过了、重试还是缺 —— 不再纠缠，退化成纯 DB 路径（P0 那条路，正确）
                        log.warn("库存缓存回源后仍缺，退化为纯 DB 路径: roomTypeId={}, {}~{}",
                                roomTypeId, from, to);
                        return ReserveResult.miss();
                    }
                    break;
                default:
                    log.error("库存预占脚本返回未知状态: {}", res);
                    return ReserveResult.miss();
            }
        }

        log.warn("库存缓存回源后仍缺，退化为纯 DB 路径: roomTypeId={}, dates={}", roomTypeId, dates);
        return ReserveResult.miss();
    }

    // =========================================================================
    // 释放 / 补偿
    // =========================================================================

    /** 释放单独一天（改期只释放差集里的某几天，所以粒度就是一天） */
    public ReleaseStatus releaseOne(Long roomTypeId, LocalDate date, int n) {
        if (roomTypeId == null || date == null || n <= 0) {
            return ReleaseStatus.NOT_CACHED;
        }
        String key = RedisKeyConstants.inventoryMonthKey(roomTypeId, date);
        List<String> res;
        try {
            res = redis.execute(releaseScript, List.of(key),
                    new Object[]{date.toString(), String.valueOf(n)});
        } catch (Exception e) {
            log.warn("库存释放脚本执行失败: roomTypeId={}, date={}, err={}", roomTypeId, date, e.getMessage());
            return ReleaseStatus.NOT_CACHED;
        }

        String status = (res == null || res.isEmpty()) ? "NOTCACHED" : res.get(0);
        if ("OK".equals(status)) {
            return ReleaseStatus.OK;
        }
        if ("ANOMALY".equals(status)) {
            log.error("库存释放异常（缓存与 DB 漂移）: roomTypeId={}, date={}, cachedSold={}, n={}",
                    roomTypeId, date, res.size() > 2 ? res.get(2) : "?", n);
            return ReleaseStatus.ANOMALY;
        }
        return ReleaseStatus.NOT_CACHED;
    }

    /**
     * 批量释放（事务回滚补偿用）
     *
     * @return 实际扣减成功的天数
     */
    public int releaseAll(Long roomTypeId, Collection<LocalDate> dates, int n) {
        if (dates == null || dates.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (LocalDate d : dates) {
            if (releaseOne(roomTypeId, d, n) == ReleaseStatus.OK) {
                ok++;
            }
        }
        return ok;
    }

    // =========================================================================
    // 回源 / 重建 / 对账
    // =========================================================================

    /**
     * 从 DB 回源补齐 {@code [from, to)}：<b>只补缺失的字段</b>（HSETNX），不覆盖已有值
     *
     * <p>DB 里没有库存行的日期按 <b>sold = 0</b> 处理 —— 没有行就意味着"还没人订过"，
     * 这跟 {@code checkAvailability} 的语义保持一致。
     *
     * @return 新写入的天数
     */
    /**
     * ★ 单飞回源（single-flight）：同一个「房型 + 区间」同时缺缓存时，只让一个去查库
     *
     * <h3>要解决什么</h3>
     * 库存缓存的月度桶 24 小时过期，热门房型的桶一过期，可能几十个请求同时
     * {@code HGET} miss —— <b>每个都去查一次库</b>。
     * {@code HSETNX} 保证了它们不会互相覆盖（这是 P1 做对的），
     * 但没阻止"重复查询"：100 个并发 = 100 次同样的 SQL。
     *
     * <h3>做法</h3>
     * <pre>
     *   抢到锁  -> 自己回源，填好缓存，放锁
     *   没抢到  -> 说明别人正在回源：睡一小会儿，然后让调用方重试 Lua（通常已经能命中）
     * </pre>
     *
     * <h3>为什么"没抢到"也算成功</h3>
     * 等待是有上限的（{@code inv-load-wait-rounds × inv-load-wait-interval-ms}，默认 150ms）。
     * 等完重试 Lua 如果还是 MISS，调用方会<b>退化成纯 DB 路径</b>（P0 那条路，结果仍然正确）。
     * 也就是说：最坏情况是"这次没享受到缓存"，绝不会卡住、绝不会报错、绝不会丢数据。
     *
     * <p>这一点很关键：<b>加锁是为了性能，不是为了正确性</b>。
     * 所以锁拿不到时该放行，而不是拒绝服务。
     */
    private void loadWithSingleFlight(Long roomTypeId, LocalDate from, LocalDate to) {
        String lockKey = RedisKeyConstants.inventoryLoadLockKey(roomTypeId, from, to);
        String token = lock.tryLock(lockKey, loadLockTtlSeconds);

        if (token != null) {
            try {
                int loaded = loadRangeFromDb(roomTypeId, from, to);
                log.debug("库存缓存回源[我抢到锁]: roomTypeId={}, {}~{} 新补 {} 天",
                        roomTypeId, from, to, loaded);
            } finally {
                lock.unlock(lockKey, token);
            }
            return;
        }

        // 没抢到锁：别人正在回源，等它填好。
        //
        // ★ 不能"死睡固定时长"：持有者要查一次库，耗时不定（本项目 DB 走 SSH 隧道，
        //   一次查询就要好几百毫秒）。睡 150ms 就放弃的话，等待者会在持有者填完之前
        //   全部退化成纯 DB 路径 —— single-flight 等于白做（实测就是这样）。
        //
        //   所以换成"边等边看"：每轮先检查最后一天有没有写进去
        //   （持有者是按日期升序写的，最后一天出现就等于它写完了），
        //   写完立刻走，没写完就再等一小会儿，最多等 rounds × interval（默认 2 秒）。
        LocalDate lastDay = to.minusDays(1);
        String lastKey = RedisKeyConstants.inventoryMonthKey(roomTypeId, lastDay);
        long interval = Math.max(1, loadWaitIntervalMs);
        long waited = 0;
        for (int i = 0; i < Math.max(1, loadWaitRounds); i++) {
            if (Boolean.TRUE.equals(redis.opsForHash().hasKey(lastKey, lastDay.toString()))) {
                break;   // 别人写完了，赶紧去重试 Lua
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            waited += interval;
        }
        log.debug("库存缓存回源[等别人]: roomTypeId={}, {}~{} 等了 {}ms",
                roomTypeId, from, to, waited);
    }

    /**
     * ★ 带抖动的缓存 TTL（防"缓存雪崩"）
     *
     * <h3>为什么不能统一给 86400 秒</h3>
     * 所有这些 key 都是<b>懒加载</b>的：第一次被访问时才创建、同时设置过期时间。
     * 如果 TTL 是个固定值，那么"在同一个时间段被创建的一批 key"会在
     * <b>同一个时间段一起过期</b> —— 冷启动后集中访问、或一次性重建一批房型，
     * 就会造出这种"同生同死"的批次。到点它们同时失效，请求同时穿透到 DB，
     * 这就是雪崩（和击穿的区别：击穿是<b>一个热点</b>，雪崩是<b>一大批同时</b>）。
     *
     * <h3>做法：加一段随机量，把过期时间摊开</h3>
     * <pre>
     *   TTL = 基础值 + random(0, 抖动上限)
     *   默认  86400 + random(0, 3600)  =>  24h ~ 25h 之间随机
     * </pre>
     * 一批 key 的过期时间就被摊到一个小时里，而不是砸在同一秒。
     *
     * <h3>为什么用"加"而不是"加减"</h3>
     * 加法保证 TTL 永远不短于基础值 —— 抖动只可能让缓存活得更久一点，
     * 不会因为抖动把刚填好的缓存提前弄没。
     */
    private Duration cacheTtl() {
        long base = Math.max(1, cacheTtlSeconds);
        long jitter = Math.max(0, cacheTtlJitterSeconds);
        long extra = jitter == 0 ? 0 : ThreadLocalRandom.current().nextLong(jitter + 1);
        return Duration.ofSeconds(base + extra);
    }
    public int loadRangeFromDb(Long roomTypeId, LocalDate from, LocalDate to) {
        Map<LocalDate, Integer> soldMap = querySoldMap(roomTypeId, from, to);
        int created = 0;
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            String key = RedisKeyConstants.inventoryMonthKey(roomTypeId, d);
            Boolean isNew = redis.opsForHash().putIfAbsent(key, d.toString(),
                    String.valueOf(soldMap.getOrDefault(d, 0)));
            if (Boolean.TRUE.equals(isNew)) {
                created++;
            }
            redis.expire(key, cacheTtl());
        }
        return created;
    }

    /**
     * 用 DB 的值<b>覆盖</b>重建 {@code [from, to)}（运维/修复用）
     *
     * <p>警告：会冲掉 Redis 里可能存在的、比 DB 更新的预占值。
     * 只在确认 DB 是准的（比如应用停机、或排查漂移）时使用。
     *
     * @return 写入的天数
     */
    public int rebuildRangeFromDb(Long roomTypeId, LocalDate from, LocalDate to) {
        Map<LocalDate, Integer> soldMap = querySoldMap(roomTypeId, from, to);
        int n = 0;
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            String key = RedisKeyConstants.inventoryMonthKey(roomTypeId, d);
            redis.opsForHash().put(key, d.toString(), String.valueOf(soldMap.getOrDefault(d, 0)));
            redis.expire(key, cacheTtl());
            n++;
        }
        return n;
    }

    /**
     * 对账：列出 Redis 与 DB <b>真正不一致</b>的日期
     *
     * <p>★ 关键语义：「缓存里没有这天」<b>不算漂移</b>。
     * 缓存是懒加载的，没被预订过的日期本来就不会有值；真要用到时会回源 DB。
     * 所以只有"两边都有值但不一样"才需要告警。
     */
    public CompareResult compareWithDb(Long roomTypeId, LocalDate from, LocalDate to) {
        Map<LocalDate, Integer> soldMap = querySoldMap(roomTypeId, from, to);
        List<InvDiff> diffs = new ArrayList<>();
        int days = 0;
        int notCached = 0;
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            days++;
            int dbSold = soldMap.getOrDefault(d, 0);
            Object v = redis.opsForHash().get(RedisKeyConstants.inventoryMonthKey(roomTypeId, d), d.toString());
            if (v == null) {
                notCached++;
                continue;
            }
            int cached = Integer.parseInt(v.toString());
            if (cached != dbSold) {
                diffs.add(new InvDiff(d, cached, dbSold));
            }
        }
        return new CompareResult(days, notCached, diffs);
    }

    /** 只读地看 Redis 里的库存（排查用）。value 为 null 表示缓存里没有这天。 */
    public Map<String, Integer> dump(Long roomTypeId, LocalDate from, LocalDate to) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            Object v = redis.opsForHash().get(RedisKeyConstants.inventoryMonthKey(roomTypeId, d), d.toString());
            out.put(d.toString(), v == null ? null : Integer.valueOf(v.toString()));
        }
        return out;
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private Map<LocalDate, Integer> querySoldMap(Long roomTypeId, LocalDate from, LocalDate to) {
        List<RoomInventory> rows = roomInventoryMapper.selectRange(roomTypeId, from, to);
        Map<LocalDate, Integer> map = new HashMap<>();
        for (RoomInventory r : rows) {
            map.put(r.getStayDate(), r.getSoldRooms() == null ? 0 : r.getSoldRooms());
        }
        return map;
    }
}
