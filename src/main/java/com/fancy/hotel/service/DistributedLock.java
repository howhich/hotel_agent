package com.fancy.hotel.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 分布式锁（最小可用版）
 *
 * <h3>用在哪</h3>
 * P3.2 的 single-flight：缓存缺失时只放一个请求去回源，其它等着。
 * 单实例下用 {@code synchronized} 也能做到，但用 Redis 有个好处：
 * <b>以后开多实例不用改代码</b>。
 *
 * <h3>这个锁是什么、不是什么</h3>
 * <table border="1">
 *   <caption>能力边界</caption>
 *   <tr><th>有</th><th>没有</th></tr>
 *   <tr>
 *     <td>互斥（{@code SET NX}）、过期自动释放（{@code EX}）、安全释放（比对 token）</td>
 *     <td>自动续期（看门狗）、可重入、公平性、Redlock 多节点容错</td>
 *   </tr>
 * </table>
 * <p>所以它只适合"短、不重入、允许偶尔失效也不出事"的临界区 ——
 * 回源补缓存正好符合：就算两个人同时回源，也不会写坏数据（HSETNX）。
 *
 * <p><b>反过来，扣库存绝不能靠这种锁</b>：那需要严格互斥，
 * 用条件 UPDATE 才是对的（判断和执行在一条 SQL 里）。
 *
 * <h3>Redis 挂了怎么办</h3>
 * {@link #tryLock} 返回 {@code null}（= 没抢到），调用方按"没抢到"处理：
 * 短等一会儿，然后自己兜底。也就是<b>降级成"没有 single-flight"</b>，
 * 而不是报错，更不是死锁。
 */
@Slf4j
@Service
@SuppressWarnings("rawtypes")
public class DistributedLock {

    private final StringRedisTemplate redis;
    private final RedisScript<List> unlockScript;

    public DistributedLock(
            StringRedisTemplate redis,
            @Qualifier("lockUnlockScript") RedisScript<List> unlockScript) {
        this.redis = redis;
        this.unlockScript = unlockScript;
    }

    /**
     * 尝试抢锁（不等待）
     *
     * @param key        锁的 key
     * @param ttlSeconds 锁的保命时长 —— 到点自动释放，防止持有者挂掉后锁永不过期
     * @return 抢到时返回 <b>本次的 token</b>（释放时要用）；没抢到返回 {@code null}
     */
    public String tryLock(String key, long ttlSeconds) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(Math.max(1, ttlSeconds)));
            if (Boolean.TRUE.equals(ok)) {
                log.debug("抢到锁: {}", key);
                return token;
            }
            return null;
        } catch (Exception e) {
            // Redis 不可用：当作没抢到，让调用方走"降级"分支
            log.warn("抢锁失败（Redis 不可用，本次退化为不加锁）: key={}, err={}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 安全释放：token 对不上就不删
     *
     * <p>必须比对 token，否则可能删掉别人的锁：
     * 自己的锁 TTL 到期 → 别人抢到了同名锁 → 自己再 DEL，删的就是别人的。
     *
     * @return 是否真的删掉了
     */
    public boolean unlock(String key, String token) {
        if (token == null) {
            return false;
        }
        try {
            List<String> res = redis.execute(unlockScript, List.of(key), token);
            boolean ok = res != null && !res.isEmpty() && "1".equals(String.valueOf(res.get(0)));
            if (ok) {
                log.debug("释放锁: {}", key);
            } else {
                log.debug("锁已不是自己的（过期后被别人抢走了），不删: {}", key);
            }
            return ok;
        } catch (Exception e) {
            // 释放失败不要紧：TTL 到点会自动消失
            log.warn("释放锁失败（等 TTL 自动过期）: key={}, err={}", key, e.getMessage());
            return false;
        }
    }
}
