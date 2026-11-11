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
    private static final String DELAY_PREFIX = "hotel:delay";
    private static final String GEO_PREFIX = "hotel:geo";
    private static final String ABSENT_PREFIX = "hotel:absent";
    private static final String LOCK_PREFIX = "hotel:lock";
    private static final String PRICE_PREFIX = "hotel:price";
    private static final String TOKEN_PREFIX = "hotel:token";

    /**
     * 登录 token -> 用户信息（P4.1）
     *
     * <pre>
     *   key   hotel:token:{随机串}
     *   value userId:username:role
     *   TTL   会话时长（默认 2 小时，每次请求可续期）
     * </pre>
     *
     * <p>为什么用「随机 token + Redis」而不是 JWT：
     * <ul>
     *   <li><b>可以随时踢下线</b> —— 删掉这个 key 立刻失效；JWT 签出去了就收不回来</li>
     *   <li>能查"谁在线"，也能做单点登录限制</li>
     *   <li>代价是每次请求多一次 Redis 查询 —— 本项目本来就依赖 Redis，这个代价可接受</li>
     * </ul>
     */
    public static String authTokenKey(String token) {
        return TOKEN_PREFIX + ":" + token;
    }

    /**
     * 价格缓存 bucket：<b>一个房型一个月</b>一个 hash（和库存同一个分桶方式）
     *
     * <pre>
     *   key   hotel:price:{rt:2001}:2026-11   (Hash)
     *   field 2026-11-16
     *   value 400.00   ——  或者是 "-"，表示"这天查过了、确实没有价格日历"
     * </pre>
     *
     * <p>★ 那个 {@code "-"} 很关键：不区分"没缓存"和"缓存了但没有价格"，
     * 那么查一个没有价格日历的日期就会每次都回源查库（穿透）。
     * 和 P3.1 的 tombstone 是同一个思路。
     */
    public static String priceMonthKey(Long roomTypeId, java.time.YearMonth ym) {
        return PRICE_PREFIX + ":{rt:" + roomTypeId + "}:" + ym;
    }

    /**
     * 库存回源的互斥锁（P3.2 single-flight）
     *
     * <pre>
     *   hotel:lock:inv:{roomTypeId}:{from}:{to}
     * </pre>
     *
     * <p>粒度是「房型 + 日期区间」：同一批并发请求（同一个房型、同样的日期）
     * 同时发现缓存缺失时，只有抢到锁的那个去查库，其它的等它填好。
     *
     * <p>为什么不按「房型 + 月份」加锁：{@code loadRangeFromDb} 是用
     * <b>一条 SQL</b> 把整个区间查出来再逐天写入的，锁的粒度和查询的粒度要对上，
     * 否则一个区间会去抢好几把锁（还要处理加锁顺序防死锁）。
     *
     * <p>单实例下这个 key 只是"进程内互斥的跨进程版"，无所谓。
     * 将来上 Redis Cluster 时，需要给 roomTypeId 加 hash tag（{@code {2001}}），
     * 否则跨 slot。
     */
    public static String inventoryLoadLockKey(Long roomTypeId, java.time.LocalDate from, java.time.LocalDate to) {
        return LOCK_PREFIX + ":inv:" + roomTypeId + ":" + from + ":" + to;
    }

    /**
     * 「不存在」标记（防缓存穿透）
     *
     * <pre>
     *   hotel:absent:booking:{bookingId}
     *   hotel:absent:roomtype:{roomTypeId}
     *   hotel:absent:hotel:{hotelId}
     * </pre>
     *
     * <p>★ 只记「这个 id 查过、确实不存在」，<b>不缓存正本数据</b>。
     *
     * <p>这是刻意的克制：如果连正本也缓存，就要处理"数据改了缓存什么时候失效"，
     * 而且写路径（下单、改期）可能读到过期的 {@code total_rooms}。
     * 只记"不存在"就没有这些问题 —— 命中正本永远走 DB，拿到的永远是最新的。
     *
     * <p>★ TTL 必须短（默认 60 秒）：万一这个 id 之后真的被创建了
     * （比如后台补录数据），最多 60 秒就自动恢复正常，不需要人工清缓存。
     */
    public static String absentBookingKey(long bookingId) {
        return ABSENT_PREFIX + ":booking:" + bookingId;
    }

    public static String absentRoomTypeKey(long roomTypeId) {
        return ABSENT_PREFIX + ":roomtype:" + roomTypeId;
    }

    public static String absentHotelKey(long hotelId) {
        return ABSENT_PREFIX + ":hotel:" + hotelId;
    }

    /**
     * 订单超时延迟队列（ZSet）
     *
     * <pre>
     *   key    hotel:delay:order      (ZSet)
     *   score  触发时刻的 epoch 毫秒
     *   member bookingId
     * </pre>
     *
     * <p>★ 这个 key【刻意不设 TTL】，因为它是"待办任务"而不是缓存 ——
     * 整体过期就意味着任务集体丢失。
     *
     * <p>但也因此，在 {@code allkeys-lru} 淘汰策略下它跟普通缓存一样会被淘汰，
     * 任务就丢了。所以有两道保险：
     * <ol>
     *   <li>服务器 Redis 的淘汰策略改成 {@code volatile-lru} ——
     *       不设 TTL 的键永不被淘汰，正好保护它（设了 TTL 的库存缓存照旧可淘汰）</li>
     *   <li>DB 扫表兜底（{@code OrderTimeoutJob.sweep()}），真丢了也能捞回来</li>
     * </ol>
     */
    public static String orderDelayQueueKey() {
        return DELAY_PREFIX + ":order";
    }

    /**
     * 酒店地理索引（GEO，member = hotelId）
     *
     * <pre>
     *   GEOADD    hotel:geo:all &lt;lng&gt; &lt;lat&gt; &lt;hotelId&gt;
     *   GEOSEARCH hotel:geo:all FROMLONLAT &lt;lng&gt; &lt;lat&gt; BYRADIUS &lt;km&gt; km ASC WITHDIST
     * </pre>
     *
     * <p>用<b>单个 key</b> 而不是按城市分：跨城市、跨城市边界都能搜到；
     * 数据量小的时候完全够用。分片（按城市 hash）留到酒店数上万再考虑。
     *
     * <p>它是从 {@code hotel} 表派生的索引，可重建，所以被淘汰也无所谓 ——
     * 见 {@code POST /booking/hotel/geo/rebuild}。
     */
    public static String hotelGeoKey() {
        return GEO_PREFIX + ":all";
    }

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
