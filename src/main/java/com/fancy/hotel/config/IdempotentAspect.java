package com.fancy.hotel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.hotel.common.BusinessException;
import com.fancy.hotel.common.Idempotent;
import com.fancy.hotel.common.RedisKeyConstants;
import com.fancy.hotel.common.UserContext;
import com.fancy.hotel.domain.entity.IdempotentRecord;
import com.fancy.hotel.mapper.IdempotentRecordMapper;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 幂等切面
 *
 * <p>执行顺序概览：
 * <pre>
 *   Redis SET NX  ──失败──> 回放首次结果（或报"处理中"）
 *        │成功
 *   DB 插唯一键   ──撞键──> 回放首次结果（第一层没兜住时）
 *        │成功
 *   执行业务方法  ──抛异常──> 删掉记录 + Redis 键（让用户能重试）→ 原样抛出
 *        │成功
 *   记录结果（DB.result_ref + Redis DONE:&lt;ref&gt;）→ 返回
 * </pre>
 *
 * <p>★ {@code @Order} 必须早于 {@code @Transactional} —— 否则切面会被包在事务里，
 * 那次"插入幂等记录"就会跟着业务事务一起回滚，第二层防御直接失效。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class IdempotentAspect {

    private static final String HEADER_NAME = "Idempotency-Key";
    private static final String DONE_PREFIX = "DONE:";
    private static final String RUNNING = "RUNNING";

    private final StringRedisTemplate redis;
    private final IdempotentRecordMapper idemMapper;
    private final ObjectMapper objectMapper;

    /** 回放结果 */
    private record Replay(boolean found, Object value) {
        static final Replay NONE = new Replay(false, null);
    }

    @Around("@annotation(idem)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idem) throws Throwable {
        String key;
        try {
            key = RedisKeyConstants.idempotentKey(idem.bizType(), fingerprint(pjp));
        } catch (Exception e) {
            // 造不出键就放行（fail-open）：不能因为幂等机制自身的问题挡住正常业务
            log.warn("构造幂等键失败，本次跳过幂等: {}", e.getMessage());
            return pjp.proceed();
        }
        long ttl = Math.max(1, idem.ttlSeconds());

        // ---------- 第一层：Redis SET NX ----------
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key, RUNNING, Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("Redis 不可用，幂等降级为仅靠 DB 唯一键: {}", e.getMessage());
            acquired = Boolean.TRUE;      // 放行，让第二层去兜
        }

        if (!Boolean.TRUE.equals(acquired)) {
            Replay r = tryReplay(key, pjp);
            if (r.found()) {
                return r.value();
            }
            throw BusinessException.conflict("请求正在处理中，请勿重复提交");
        }

        // ---------- 第二层：DB 唯一键兜底 ----------
        try {
            idemMapper.insert(IdempotentRecord.builder()
                    .idemKey(key)
                    .bizType(idem.bizType())
                    .status(IdempotentRecord.STATUS_RUNNING)
                    .expireTime(LocalDateTime.now().plusSeconds(ttl))
                    .build());
        } catch (DuplicateKeyException e) {
            // 说明这个请求之前已经进来过（Redis 那条可能是被淘汰了）
            safeCleanup(key);
            Replay r = tryReplay(key, pjp);
            if (r.found()) {
                return r.value();
            }
            throw BusinessException.conflict("请勿重复提交同样的请求");
        }

        // ---------- 执行 ----------
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable t) {
            // 失败要清掉记录，否则用户重试还会被当成重复提交
            safeCleanup(key);
            throw t;
        }

        // ---------- 记录结果 ----------
        String ref = result == null ? "" : String.valueOf(result);
        try {
            idemMapper.markDone(key, ref);
            redis.opsForValue().set(key, DONE_PREFIX + ref, Duration.ofSeconds(ttl));
        } catch (Exception e) {
            // 业务已经成功了，记录失败只是让"下一次重复请求"回放不了，
            // 不该因此把成功的下单变成失败
            log.error("幂等结果记录失败（业务已成功）: key={}, result={}", key, ref, e);
        }
        return result;
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private Replay tryReplay(String key, ProceedingJoinPoint pjp) {
        String ref = null;

        // 先看 Redis（快）
        try {
            String v = redis.opsForValue().get(key);
            if (v != null && v.startsWith(DONE_PREFIX)) {
                ref = v.substring(DONE_PREFIX.length());
            }
        } catch (Exception e) {
            log.warn("读 Redis 幂等键失败: {}", e.getMessage());
        }

        // Redis 没有再查 DB（可能被 allkeys-lru 淘汰了）
        if (ref == null) {
            try {
                IdempotentRecord r = idemMapper.selectByKey(key);
                if (r != null && r.getStatus() != null && r.getStatus() == IdempotentRecord.STATUS_DONE) {
                    ref = r.getResultRef();
                }
            } catch (Exception e) {
                log.warn("读幂等记录失败: {}", e.getMessage());
            }
        }

        if (ref == null) {
            return Replay.NONE;
        }

        Object value = convertRef(ref, pjp);
        markReplayHeader();
        log.info("幂等命中，回放首次结果: key={}, result={}", key, ref);
        return new Replay(true, value);
    }

    /** 把存下来的字符串还原成方法的返回类型 */
    private Object convertRef(String ref, ProceedingJoinPoint pjp) {
        Class<?> rt = ((MethodSignature) pjp.getSignature()).getReturnType();
        if (rt == void.class || rt == Void.class) {
            return null;
        }
        if (rt == String.class) {
            return ref;
        }
        if (rt == Long.class || rt == long.class) {
            return Long.valueOf(ref);
        }
        if (rt == Integer.class || rt == int.class) {
            return Integer.valueOf(ref);
        }
        throw new IllegalStateException(
                "@Idempotent 暂不支持返回类型 " + rt.getName() + " 的结果回放；"
                        + "要支持就扩展 convertRef()");
    }

    /**
     * 构造幂等键的指纹部分
     *
     * <p>优先用客户端给的 {@code Idempotency-Key}（重试时最可靠）；
     * 没传就用参数指纹 —— 同一份请求体算同一个请求。
     */
    private String fingerprint(ProceedingJoinPoint pjp) throws Exception {
        // ★ 必须把「当前登录用户」算进指纹。
        //
        //   P4.1 之后 userId 不再从入参取（改成从 token 取），如果指纹里没有用户身份，
        //   两个不同用户提交【完全相同的请求体】就会算出同一个幂等键 ——
        //   后一个人会被当成"重复提交"，回放前一个人的 bookingId。
        //   后果：既漏了别人的订单号，又少建了一单。（这是 P4 改身份来源时引入的）
        //
        //   没有登录上下文时（内部调用/定时任务）拼空前缀，行为不变。
        String userPart = currentUserPart();

        String clientKey = headerValue(HEADER_NAME);
        if (clientKey != null && !clientKey.isBlank()) {
            return userPart + "h:" + clientKey.trim();
        }
        List<Object> keep = new ArrayList<>();
        for (Object a : pjp.getArgs()) {
            if (a instanceof ServletRequest || a instanceof ServletResponse) {
                continue;                       // servlet 对象不可序列化，剔除
            }
            keep.add(a);
        }
        String json = objectMapper.writeValueAsString(keep.toArray());
        return userPart + "f:" + sha256Hex(json).substring(0, 32);
    }

    /** 指纹里的用户前缀，例如 {@code u9001_}；没有登录用户时为空串 */
    private String currentUserPart() {
        try {
            UserContext.CurrentUser u = UserContext.get();
            if (u != null && u.userId() != null) {
                return "u" + u.userId() + "_";
            }
        } catch (Exception ignored) {
            // 忽略：拿不到身份时按"无身份"处理
        }
        return "";
    }

    private String headerValue(String name) {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            return attrs.getRequest().getHeader(name);
        } catch (Exception e) {
            return null;
        }
    }

    /** 标记这是回放，方便客户端/排查时区分 */
    private void markReplayHeader() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                attrs.getResponse().setHeader("X-Idempotent-Replay", "true");
            }
        } catch (Exception ignored) {
            // 非 Web 上下文，忽略
        }
    }

    private void safeCleanup(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("清理 Redis 幂等键失败: {}", e.getMessage());
        }
        try {
            idemMapper.deleteIfRunning(key);
        } catch (Exception e) {
            log.warn("清理幂等记录失败: {}", e.getMessage());
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
