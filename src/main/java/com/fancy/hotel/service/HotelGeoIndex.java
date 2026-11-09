package com.fancy.hotel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.hotel.common.RedisKeyConstants;
import com.fancy.hotel.domain.entity.Hotel;
import com.fancy.hotel.mapper.HotelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 酒店地理索引（Redis GEO）
 *
 * <pre>
 *   GEOADD    hotel:geo:all &lt;lng&gt; &lt;lat&gt; &lt;hotelId&gt;
 *   GEOSEARCH hotel:geo:all FROMLONLAT &lt;lng&gt; &lt;lat&gt; BYRADIUS &lt;km&gt; km ASC WITHDIST
 * </pre>
 *
 * <p><b>定位：从 {@code hotel} 表派生的索引，可随时重建。</b>
 * 被淘汰/清空都不影响正确性，只是查不到东西 —— 重建即可
 * （{@code POST /booking/hotel/geo/rebuild}，或重启时预热）。
 *
 * <p>用<b>单个 key</b> 而不是按城市分：跨城市、跨城市边界都能搜到。数据量小完全够。
 *
 * <p>用现代的 {@code GEOSEARCH} 而不是已弃用的 {@code GEORADIUS}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotelGeoIndex {

    private final StringRedisTemplate redis;
    private final HotelMapper hotelMapper;

    @Value("${hotel.nearby.rebuild-on-startup:true}")
    private boolean rebuildOnStartup;

    /**
     * 一条命中：酒店 ID + 直线距离（公里）
     *
     * <p>★ {@code distanceKm} 用包装类型 {@code Double} 而不是 {@code double}，
     * 因为 <b>null 表示"距离未知"，和 0 完全不是一回事</b>。
     * 0 是合法值 —— 查询中心正好落在某家酒店的坐标上时，距离就是 0（本项目就测过）。
     */
    public record GeoHit(long hotelId, Double distanceKm) {
    }

    private static String key() {
        return RedisKeyConstants.hotelGeoKey();
    }

    // =========================================================================
    // 写入
    // =========================================================================

    /**
     * 写入/更新一个酒店的坐标
     *
     * <p>返回是否成功。<b>不用 GEOADD 的返回值判断</b>：它返回"新增的成员数"，
     * 更新已有成员时返回 0，拿去当成败判断是错的。
     */
    public boolean put(long hotelId, double longitude, double latitude) {
        try {
            redis.opsForGeo().add(key(), new Point(longitude, latitude), String.valueOf(hotelId));
            return true;
        } catch (Exception e) {
            log.error("GEO 写入失败: hotelId={}, lng={}, lat={}", hotelId, longitude, latitude, e);
            return false;
        }
    }

    /**
     * 从 DB 全量重建（只索引<b>上架</b>的酒店）
     *
     * <p>先 {@code DEL} 再写：否则下架/删除的酒店会永远残留在索引里，
     * 查出来之后再去 DB 找不到对应行。
     *
     * @return 成功写入的酒店数
     */
    public int rebuildFromDb() {
        List<Hotel> hotels = hotelMapper.selectList(new LambdaQueryWrapper<Hotel>()
                .eq(Hotel::getStatus, 1));

        try {
            redis.delete(key());
        } catch (Exception e) {
            log.warn("清空 GEO 索引失败（继续写入）: {}", e.getMessage());
        }

        int n = 0;
        for (Hotel h : hotels) {
            if (h.getHotelId() == null || h.getLongitude() == null || h.getLatitude() == null) {
                continue;
            }
            if (put(h.getHotelId(), h.getLongitude().doubleValue(), h.getLatitude().doubleValue())) {
                n++;
            }
        }
        log.info("酒店 GEO 索引重建完成：DB 里 {} 家上架酒店，成功写入 {} 家", hotels.size(), n);
        return n;
    }

    // =========================================================================
    // 查询
    // =========================================================================

    /**
     * 半径查询，按距离升序
     *
     * @param longitude 中心点经度
     * @param latitude  中心点纬度
     * @param radiusKm  半径（公里）
     * @param limit     最多返回多少条
     * @return 命中列表；索引为空或 Redis 不可用时返回空列表（不抛异常）
     */
    public List<GeoHit> search(double longitude, double latitude, double radiusKm, int limit) {
        try {
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(
                    key(),
                    GeoReference.fromCoordinate(longitude, latitude),
                    new Distance(radiusKm, Metrics.KILOMETERS),
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                            .includeDistance()
                            .sortAscending()
                            .limit(Math.max(1, limit)));

            if (results == null || results.getContent() == null) {
                return List.of();
            }

            List<GeoHit> hits = new ArrayList<>();
            int noDistance = 0;
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> r : results.getContent()) {
                String member = r.getContent().getName();

                // ★ 距离必须区分"未知"和"0"。
                //
                //   原来的写法是 `getDistance() == null ? 0d : ...`，看着无害，其实是个陷阱：
                //     - 0 是合法距离（查询中心正好在酒店坐标上）
                //     - 默认成 0 之后，这条结果会按"距离升序"排到第一个，被当成最近的一家
                //     - 同时还显示成 "0.0 公里"
                //   也就是说，兜底兜到了"最不容易被发现"的方向：
                //   数据是错的，但看起来最合理。
                //
                //   而且这个兜底会掩盖真正的配置错误：如果有人重构时把
                //   includeDistance() 删掉，所有结果都不带距离，整个列表就会按任意
                //   顺序返回、却对外声称"最近的在前"。
                //
                //   所以这里保留 null（= 未知），交给排序把"未知"放到最后，
                //   并且记一条 warn —— 让它可见，而不是伪装成正常数据。
                //
                //   ★ 实测补充（变异测试验过）：把 includeDistance() 去掉之后，
                //   Spring 并不会返回 null，而是给 Distance(0) —— 也就是"全部 0 公里"。
                //   这种情况下面的 null 分支根本不会走到。所以真正的守卫是
                //   p2_geo_test 里那条 "距离为 0 的酒店最多只能有一家"：
                //   查询中心只可能正好落在某一家酒店的坐标上，出现两家以上就是
                //   WITHDIST 丢了。
                Double km = r.getDistance() == null ? null : r.getDistance().getValue();
                if (km == null) {
                    noDistance++;
                }
                try {
                    hits.add(new GeoHit(Long.parseLong(member), km));
                } catch (NumberFormatException e) {
                    log.warn("GEO 索引里有非法成员（不是 hotelId），已忽略: {}", member);
                }
            }
            if (noDistance > 0) {
                log.warn("GEO 返回了 {} 条不带距离的结果（本不该发生）。"
                        + "最常见的原因是 GEOSEARCH 少了 WITHDIST —— 检查 includeDistance() 是否还在。",
                        noDistance);
            }
            return hits;
        } catch (Exception e) {
            log.error("GEO 查询失败: lng={}, lat={}, radius={}km", longitude, latitude, radiusKm, e);
            return List.of();
        }
    }

    /** 索引里一共有多少个成员（-1 = Redis 不可用） */
    public long size() {
        try {
            Long n = redis.opsForZSet().zCard(key());
            return n == null ? 0L : n;
        } catch (Exception e) {
            log.warn("读取 GEO 索引大小失败: {}", e.getMessage());
            return -1L;
        }
    }

    // =========================================================================
    // 启动预热
    // =========================================================================

    /**
     * 启动时预热索引
     *
     * <p>索引是可重建的派生数据，但"空索引"会让附近查询静默返回空列表 ——
     * 与其让调用方困惑，不如启动时就建好。
     *
     * <p>失败不影响启动（Redis 没起来时也应该能启动，只是查询暂时没结果）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        if (!rebuildOnStartup) {
            return;
        }
        try {
            int n = rebuildFromDb();
            log.info("启动预热：酒店 GEO 索引 {} 家", n);
        } catch (Exception e) {
            log.warn("启动预热 GEO 索引失败（不影响启动）: {}", e.getMessage());
        }
    }
}
