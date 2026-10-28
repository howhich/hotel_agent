package com.fancy.hotel.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口幂等
 *
 * <p>标在 service 方法上，由 {@code IdempotentAspect} 拦截。语义：
 * <b>同一个请求重复提交，只真正执行一次，重复的那次返回首次的结果。</b>
 *
 * <p>幂等键的来源（二选一，优先客户端提供）：
 * <ol>
 *   <li>请求头 {@code Idempotency-Key} —— 客户端重试时最能保证"同一次提交"</li>
 *   <li>方法参数的指纹（SHA-256）—— 不传头时，同一份请求体就算同一个请求</li>
 * </ol>
 *
 * <p>两层防御：Redis {@code SET NX}（快，扛并发）+ {@code idempotent_record} 唯一键
 * （Redis 挂了、或跨实例时兜底）。
 *
 * <p>⚠️ 执行<b>失败</b>时会删掉记录，让同一个请求可以重试 —— 因为本项目的业务方法
 * 都是 {@code @Transactional}，失败就等于没有任何副作用。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 业务类型，参与幂等键构造，例如 {@code CREATE_BOOKING} */
    String bizType();

    /** 幂等窗口（秒）。窗口内相同请求只执行一次。 */
    int ttlSeconds() default 600;
}
