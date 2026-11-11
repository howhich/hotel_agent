package com.fancy.hotel.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.hotel.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 实现（P4.3）
 *
 * <p>DeepSeek 的接口是 OpenAI 兼容格式，所以这个类换成 OpenAI / 通义也一样能用，
 * 只需要改 base-url 和 model。
 *
 * <h3>为什么用 response_format = json_object</h3>
 * 让模型"只输出 JSON"是能显著降低解析失败率的做法。但即便如此也不能全信它 ——
 * 仍然要做兜底：去掉可能的 ```json 包裹，解析失败就抛 502 而不是把脏数据往下传。
 *
 * <h3>key 缺失时的行为</h3>
 * {@link #isAvailable()} 返回 false，调用方回 <b>503</b>（服务不可用），
 * 而不是 500。这样"没配 key"和"模型调挂了"能区分开。
 */
@Slf4j
@Service
public class DeepSeekLlmClient implements LlmClient {

    private final ObjectMapper objectMapper;
    private final String model;
    private final String apiKey;
    private final RestClient rest;

    public DeepSeekLlmClient(
            ObjectMapper objectMapper,
            @Value("${hotel.agent.api-key:}") String apiKey,
            @Value("${hotel.agent.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${hotel.agent.model:deepseek-chat}") String model,
            @Value("${hotel.agent.timeout-seconds:60}") int timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.model = model;
        this.apiKey = apiKey == null ? "" : apiKey.trim();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)));

        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + this.apiKey)
                .build();

        if (this.apiKey.isEmpty()) {
            log.warn("hotel.agent.api-key 未配置 —— /agent/chat 会返回 503。"
                    + "本地启动前先 dot-source devops/local-env.ps1");
        }
    }

    @Override
    public boolean isAvailable() {
        return !apiKey.isEmpty();
    }

    @Override
    public String completeJson(String systemPrompt, String userMessage) {
        if (!isAvailable()) {
            throw new BusinessException(503,
                    "Agent 未启用：缺少 hotel.agent.api-key（请设置环境变量 DEEPSEEK_API_KEY）");
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", 500);
        body.put("temperature", 0);                      // 结构化输出要稳定，别让它发挥
        body.put("response_format", Map.of("type", "json_object"));

        String raw;
        long start = System.currentTimeMillis();
        try {
            raw = rest.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("调用大模型失败: model={}, err={}", model, e.getMessage());
            throw new BusinessException(502, "大模型调用失败：" + e.getMessage());
        }
        long cost = System.currentTimeMillis() - start;

        String content = extractContent(raw);
        log.info("大模型返回: model={}, 耗时={}ms, 内容长度={}", model, cost,
                content == null ? 0 : content.length());
        return content;
    }

    /**
     * 从 OpenAI 格式的响应里取出 content
     *
     * <p>顺带解开模型偶尔加的 ```json 包裹 —— 即使要求了"只输出 JSON"，
     * 它有时候还是会包一层 markdown。
     */
    private String extractContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(502, "大模型返回为空");
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String content = contentNode.isMissingNode() ? null : contentNode.asText(null);
            if (content == null || content.isBlank()) {
                throw new BusinessException(502, "大模型返回里没有 content：" + brief(raw));
            }
            content = content.trim();
            if (content.startsWith("```")) {
                int firstNewline = content.indexOf('\n');
                int lastFence = content.lastIndexOf("```");
                if (firstNewline > 0 && lastFence > firstNewline) {
                    content = content.substring(firstNewline + 1, lastFence).trim();
                }
            }
            return content;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析大模型响应失败: {}", brief(raw));
            throw new BusinessException(502, "大模型返回格式无法解析");
        }
    }

    private static String brief(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
