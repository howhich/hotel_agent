package com.fancy.hotel.controller;

import com.fancy.hotel.common.Result;
import com.fancy.hotel.common.UserContext;
import com.fancy.hotel.domain.dto.AgentChatDTO;
import com.fancy.hotel.service.agent.AgentService;
import com.fancy.hotel.service.agent.AgentSessionStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 酒店预订 Agent（P4.3）
 *
 * <pre>
 *   POST /agent/chat   {sessionId?, message}  → {sessionId, intent, reply, slots, missing, stage, candidates}
 *   POST /agent/reset  {sessionId}            → 丢掉这个会话
 * </pre>
 *
 * <p>业务接口都要 token，所以 Agent 也知道"我是谁" —— 第六步真正下单时，
 * 身份直接来自 {@code UserContext}，不需要模型告诉它。
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final AgentSessionStore sessionStore;

    @PostMapping("/chat")
    public Result chat(@Valid @RequestBody AgentChatDTO dto) {
        return Result.ok(agentService.chat(UserContext.requireUserId(), dto));
    }

    /**
     * 重置会话（用户想换个话题重来）
     *
     * <p>★ 要校验会话归属：{@code sessionId} 虽然是 16 位十六进制、猜不到，
     * 但"猜不到"不是"可以不校验"。订单号也猜不到，我们照样校验。
     * 别人不能帮你清会话，正如别人不能帮你取消订单。
     */
    @PostMapping("/reset")
    public Result reset(@RequestBody(required = false) AgentChatDTO dto) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (dto != null) {
            boolean cleared = sessionStore.clearIfOwned(dto.getSessionId(), UserContext.requireUserId());
            if (!cleared) {
                throw com.fancy.hotel.common.BusinessException.forbidden("无权清空别人的会话");
            }
            out.put("cleared", dto.getSessionId());
        } else {
            out.put("cleared", null);
        }
        return Result.ok(out);
    }
}
