package com.fancy.hotel.domain.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 会话状态（P4.3 起）
 *
 * <p>存在 Redis 里（JSON），一次对话从 Controller 到 Service 都靠它串起来。
 *
 * <h3>为什么要有"槽位"这个中间层</h3>
 * 用户很少一次把话说全。"帮我订房" → 缺城市、缺日期。
 * 如果每句话都直接去查库，就会查出一堆无意义的结果；
 * 先把话解析成<b>字段</b>（槽位），再看缺什么、缺就问，齐了才查 —— 这才是多轮对话的样子。
 *
 * <h3>history 是用来看的，不是用来存的</h3>
 * 最近几轮对话会被拼进发给模型的提示里（见 {@code AgentService#buildRoutePayload}）。
 * 只有槽位、没有历史的话，"再住一晚呢"这种依赖上文的省略句就解析不出来。
 */
@Data
public class AgentSession {

    private String sessionId;
    private Long userId;

    /** BOOK / QUERY / CANCEL / RESCHEDULE / CONFIRM / CHITCHAT */
    private String intent;

    /**
     * 已收集的槽位：city / checkIn / checkOut / roomCount / bedType
     *
     * <p>用 Map 而不是固定字段：加槽位不用改类，而且能原样回给前端。
     */
    private Map<String, String> slots = new LinkedHashMap<>();

    /** 最近几轮对话：{role, content}（拼进提示用，也可以回放给排查） */
    private List<Map<String, String>> history = new ArrayList<>();

    /**
     * 候选草案（P1 修正：整份存下来，不再只留最便宜那一个）
     *
     * <p>回复里会把 2~3 个候选列给用户看。如果会话只留 top1，
     * 用户说"我要国宾馆那家"就无处可选 —— 只能重新查一遍，
     * 而且重新查还是会选中同一个最便宜的。所以整份存下来。
     *
     * <p>★ 它同时也是"下单内容的唯一来源"：确认时用它，不用用户那句话、不用模型。
     */
    private List<AgentDraft> candidates = new ArrayList<>();

    /** 用户挑中的是第几个候选（0 = 最便宜那个） */
    private Integer selectedIndex = 0;

    /** 最后一次活动时间（毫秒） */
    private long updatedAt;

    /**
     * 当前选中的草案；没有候选时返回 null
     *
     * <p>注意这是普通方法（不是 get/is 开头），Jackson 不会把它当成属性序列化 ——
     * 避免往 Redis 里多写一个冗余字段。
     */
    public AgentDraft selectedDraft() {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        int i = selectedIndex == null ? 0 : selectedIndex;
        if (i < 0 || i >= candidates.size()) {
            i = 0;
        }
        return candidates.get(i);
    }

    /** 清掉草案：下单成功、过期、失败之后都要清，避免被再"确认"一次 */
    public void clearDraft() {
        candidates = new ArrayList<>();
        selectedIndex = 0;
    }
}
