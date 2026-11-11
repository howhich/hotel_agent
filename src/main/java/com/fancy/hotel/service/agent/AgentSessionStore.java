package com.fancy.hotel.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.hotel.common.RedisKeyConstants;
import com.fancy.hotel.domain.agent.AgentSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Agent 会话的读写（P4.3）
 *
 * <p>Redis 里存 JSON，TTL 30 分钟，每次对话续期。
 *
 * <p>★ 读写失败都<b>不抛异常</b>，而是退化：
 * 读失败 → 当成新会话（用户可能要重说一遍，但服务还能用）；
 * 写失败 → 记日志，本轮回复照常返回（用户看不到差异，只是下一轮接不上）。
 * 会话丢了是体验问题，不是数据问题 —— 不该因为它 500。
 */
@Slf4j
@Service
public class AgentSessionStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final long ttlSeconds;

    public AgentSessionStore(StringRedisTemplate redis,
                             ObjectMapper objectMapper,
                             @Value("${hotel.agent.session-ttl-seconds:1800}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttlSeconds = Math.max(60, ttlSeconds);
    }

    /** 载入会话；不存在（或 sessionId 为空）就新建 */
    public AgentSession loadOrCreate(String sessionId, Long userId) {
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                String json = redis.opsForValue().get(RedisKeyConstants.agentSessionKey(sessionId));
                if (json != null) {
                    AgentSession s = objectMapper.readValue(json, AgentSession.class);
                    // 会话必须属于同一个人，否则等于用别人的上下文
                    if (s.getUserId() != null && s.getUserId().equals(userId)) {
                        return s;
                    }
                    log.warn("会话 {} 属于用户 {}，当前用户 {} —— 当作新会话",
                            sessionId, s.getUserId(), userId);
                }
            } catch (Exception e) {
                log.warn("读会话失败（当作新会话）: sessionId={}, err={}", sessionId, e.getMessage());
            }
        }
        AgentSession s = new AgentSession();
        s.setSessionId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        s.setUserId(userId);
        return s;
    }

    /** 保存会话（失败只记日志） */
    public void save(AgentSession session) {
        if (session == null || session.getSessionId() == null) {
            return;
        }
        try {
            session.setUpdatedAt(System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(session);
            redis.opsForValue().set(RedisKeyConstants.agentSessionKey(session.getSessionId()),
                    json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("保存会话失败（本轮回复照常返回）: sessionId={}, err={}",
                    session.getSessionId(), e.getMessage());
        }
    }

    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            redis.delete(RedisKeyConstants.agentSessionKey(sessionId));
        } catch (Exception e) {
            log.warn("清会话失败: {}", e.getMessage());
        }
    }
}
