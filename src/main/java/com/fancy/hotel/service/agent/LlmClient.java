package com.fancy.hotel.service.agent;

/**
 * 大模型客户端（P4.3）
 *
 * <p>只做一件事：<b>给一段 system + 一句 user，拿回一段 JSON 字符串</b>。
 * 业务逻辑（意图路由、槽位、工具调用、HITL）全都不在这里 ——
 * 这样换一家模型只要换实现。
 *
 * <p>为什么强行要 JSON：Agent 的每一步都要能被程序消费（判断意图、判断缺哪些槽位），
 * 让模型"自由发挥一段话"再正则去抠，是最容易崩的做法。
 */
public interface LlmClient {

    /** 配置里有没有可用的 key */
    boolean isAvailable();

    /**
     * 要一段 JSON
     *
     * @param systemPrompt 角色与输出格式说明（要写死"只输出 JSON"）
     * @param userMessage  用户这句话（可以带上历史摘要）
     * @return 模型返回的 JSON 字符串（已去掉可能的 ```json 包裹）
     * @throws com.fancy.hotel.common.BusinessException key 没配 → 503；调用失败 → 502
     */
    String completeJson(String systemPrompt, String userMessage);
}
