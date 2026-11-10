package com.fancy.hotel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Lua 脚本 Bean
 *
 * <p>脚本放 {@code src/main/resources/lua/*.lua} 而不是 Java 字符串常量里：
 * Lua 的多行结构在字符串里拼接既难读又难改，而且没法单独用
 * {@code redis-cli --eval} 直接测。
 *
 * <p>Spring 会用 {@code EVALSHA} 执行（先 {@code SCRIPT LOAD} 拿 sha，之后走 sha），
 * 比每次传全文高效。
 */
@Configuration
public class RedisScripts {

    /**
     * 区间库存：两趟式预检预占（先全查、再全扣）
     *
     * <p>返回 {@code List<String>}：第一项是状态码，后面是细节。
     */
    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> inventoryReserveScript() {
        return RedisScript.of(new ClassPathResource("lua/inventory_reserve.lua"), List.class);
    }

    /** 区间库存：释放单独一天 */
    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> inventoryReleaseScript() {
        return RedisScript.of(new ClassPathResource("lua/inventory_release.lua"), List.class);
    }

    /**
     * 分布式锁：安全释放（比对 token 之后才删）
     *
     * <p>「先 GET 比对、再 DEL」绝不能分成两条命令 —— 中间锁可能刚好过期并被别人抢到，
     * 那一下就会删掉别人的锁。
     */
    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> lockUnlockScript() {
        return RedisScript.of(new ClassPathResource("lua/lock_unlock.lua"), List.class);
    }

    /**
     * 订单超时延迟队列：原子认领到期任务
     *
     * <p>「取 + 删」在一个脚本里 = 谁删掉谁处理，多实例安全。
     */
    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> delayClaimScript() {
        return RedisScript.of(new ClassPathResource("lua/delay_claim.lua"), List.class);
    }
}
