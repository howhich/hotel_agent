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
}
