package com.fancy.hotel.domain.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 会话状态（P4.3）
 *
 * <p>存在 Redis 里（JSON），一次对话从 Controller 到 Service 都靠它串起来。
 *
 * <h3>为什么要有"槽位"这个中间层</h3>
 * 用户很少一次把话说全。"帮我订房" → 缺城市、缺日期。
 * 如果每句话都直接去查库，就会查出一堆无意义的结果；
 * 先把话解析成<b>字段</b>（槽位），再看缺什么、缺就问，齐了才查 —— 这才是多轮对话的样子。
 *
 * <h3>history 只留最近几轮</h3>
 * 全量发给模型是白花钱，而且越长的上下文越容易跑偏。默认留 10 轮。
 */
@Data
public class AgentSession {

    private String sessionId;
    private Long userId;

    /** BOOK / QUERY / CANCEL / RESCHEDULE / CHITCHAT */
    private String intent;

    /**
     * 已收集的槽位：city / checkIn / checkOut / roomCount / bedType
     *
     * <p>用 Map 而不是固定字段：加槽位不用改类，而且能原样回给前端。
     */
    private Map<String, String> slots = new LinkedHashMap<>();

    /** 最近几轮对话：{role, content}（不发给前端的内部状态） */
    private List<Map<String, String>> history = new ArrayList<>();

    /**
     * 待确认的草案（P4.4 用）
     *
     * <p>P4.3 只把它存下来，P4.4 才会在用户说"确认"时真的下单。
     */
    private String draftJson;

    /** 最后一次活动时间（毫秒） */
    private long updatedAt;
}
