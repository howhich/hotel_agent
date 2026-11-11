package com.fancy.hotel.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.hotel.common.BusinessException;
import com.fancy.hotel.domain.agent.AgentSession;
import com.fancy.hotel.domain.dto.AgentChatDTO;
import com.fancy.hotel.domain.vo.AgentReplyVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酒店预订 Agent（P4.3）
 *
 * <pre>
 *   用户一句话
 *     → ① 让模型解析成 {意图, 槽位}（唯一用到 LLM 的地方）
 *     → ② 合并到会话里已有的槽位（多轮）
 *     → ③ 还缺就追问（不再调模型，直接模板话术）
 *     → ④ 齐了就调工具去查（可订 + 报价），给出候选
 * </pre>
 *
 * <h3>★ 为什么只有第 ① 步用模型</h3>
 * 模型擅长"把自然语言变成结构化参数"，不擅长（也不该）做业务计算。
 * 日期怎么换算、库存够不够、总价多少 —— 这些用代码算，结果是确定的、可测试的、不要钱。
 *
 * <p>把 LLM 用在"读懂人话"这一件事上，是这一阶段最重要的设计判断。
 * 很多 Agent 的毛病就出在让它去算数、去挑选项，然后结果每次都不一样。
 *
 * <h3>会话状态</h3>
 * 槽位和最近几轮对话存在 Redis（{@link AgentSessionStore}），所以"帮我订房"→"杭州"
 * →"11月20号"可以分三句话说完。
 */
@Slf4j
@Service
public class AgentService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final LlmClient llmClient;
    private final AgentSessionStore sessionStore;
    private final HotelSearchTool searchTool;
    private final ObjectMapper objectMapper;

    public AgentService(LlmClient llmClient,
                        AgentSessionStore sessionStore,
                        HotelSearchTool searchTool,
                        ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.sessionStore = sessionStore;
        this.searchTool = searchTool;
        this.objectMapper = objectMapper;
    }

    // =========================================================================

    public AgentReplyVO chat(Long userId, AgentChatDTO dto) {
        String message = dto.getMessage() == null ? "" : dto.getMessage().trim();
        if (message.isEmpty()) {
            throw BusinessException.badRequest("message 不能为空");
        }

        AgentSession session = sessionStore.loadOrCreate(dto.getSessionId(), userId);
        log.info("Agent 对话: session={}, user={}, message={}", session.getSessionId(), userId, message);

        // ---------- ① 让模型解析意图与槽位 ----------
        RouterResult router = route(session, message);

        // ---------- ② 合并槽位 ----------
        session.setIntent(router.intent);
        if (router.slots != null && !router.slots.isEmpty()) {
            session.getSlots().putAll(router.slots);
        }
        appendHistory(session, "user", message);

        // ---------- ③④ 按意图分派 ----------
        AgentReplyVO reply;
        switch (router.intent) {
            case "BOOK", "QUERY" -> reply = handleSearch(session);
            case "CANCEL" -> reply = plain(session, "UNSUPPORTED",
                    "取消订单我暂时还不会帮你办，可以在「我的订单」里直接取消。"
                            + "（自助取消接口是现成的，Agent 这一版先只做预订）");
            case "RESCHEDULE" -> reply = plain(session, "UNSUPPORTED",
                    "改期我也还不会，可以在订单详情里改。");
            default -> reply = plain(session, "CHITCHAT",
                    "我是酒店预订助手，可以帮你查房和订房。"
                            + "比如跟我说「帮我订杭州11月20号住两晚，要大床房」。");
        }

        appendHistory(session, "assistant", reply.getReply());
        sessionStore.save(session);
        return reply;
    }

    // =========================================================================
    // ① 模型解析
    // =========================================================================

    private String systemPrompt() {
        LocalDate today = LocalDate.now();
        return "你是酒店预订助手。今天是 " + today.format(DATE)
                + "（星期" + today.getDayOfWeek().getValue() + "）。\n"
                + "把「用户这句话」结合「已知信息」解析成 JSON，只输出 JSON，不要解释、不要 markdown 代码块。\n"
                + "字段：\n"
                + "- intent: BOOK(要订房) / QUERY(只是想查) / CANCEL(取消) / RESCHEDULE(改期) / CHITCHAT(闲聊)\n"
                + "- slots.city: 城市名，如 \"杭州\"；没有就 null\n"
                + "- slots.checkIn: \"YYYY-MM-DD\"；相对日期按今天换算；没有就 null\n"
                + "- slots.checkOut: \"YYYY-MM-DD\"；\"住两晚\" => 离店 = 入住 + 2 天；没有就 null\n"
                + "- slots.roomCount: 整数；没有就 null\n"
                + "- slots.bedType: \"大床\" 或 \"双床\"；没有就 null\n"
                + "示例输出：{\"intent\":\"BOOK\",\"slots\":{\"city\":\"杭州\",\"checkIn\":\""
                + today.plusDays(9).format(DATE) + "\",\"checkOut\":\""
                + today.plusDays(11).format(DATE)
                + "\",\"roomCount\":1,\"bedType\":\"大床\"}}";
    }

    private RouterResult route(AgentSession session, String message) {
        String payload = "已知信息：" + toJson(session.getSlots()) + "\n用户这句话：" + message;
        String json;
        try {
            json = llmClient.completeJson(systemPrompt(), payload);
        } catch (BusinessException e) {
            // key 没配 / 模型挂了：如实告诉用户，不要假装听懂了
            log.warn("Agent 解析失败: {}", e.getMessage());
            throw e;
        }
        return parseRouter(json);
    }

    private RouterResult parseRouter(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String intent = root.path("intent").asText("CHITCHAT").toUpperCase().trim();

            Map<String, String> slots = new LinkedHashMap<>();
            JsonNode s = root.path("slots");
            if (s.isObject()) {
                s.fields().forEachRemaining(e -> {
                    JsonNode v = e.getValue();
                    if (v == null || v.isNull()) {
                        return;
                    }
                    String text = v.asText("").trim();
                    if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) {
                        slots.put(e.getKey(), text);
                    }
                });
            }
            log.debug("解析结果: intent={}, slots={}", intent, slots);
            return new RouterResult(intent, slots);
        } catch (Exception e) {
            // 模型偶尔会返回坏 JSON。不猜，直接当闲聊回一句引导的话。
            log.warn("模型 JSON 解析失败，降级为 CHITCHAT: {} / {}", e.getMessage(), json);
            return new RouterResult("CHITCHAT", Map.of());
        }
    }

    private record RouterResult(String intent, Map<String, String> slots) {
    }

    // =========================================================================
    // ③④ 追问 / 查询
    // =========================================================================

    private AgentReplyVO handleSearch(AgentSession session) {
        Map<String, String> slots = session.getSlots();

        // 日期可能是上一轮留下的，先规整（模型偶尔给错格式或过去的时间）
        String dateError = normalizeDates(slots);
        if (dateError != null) {
            slots.remove("checkIn");
            slots.remove("checkOut");
            return collecting(session, List.of("checkIn", "checkOut"),
                    "日期我没理解对（" + dateError + "），麻烦再说一下入住和离店时间？");
        }

        List<String> missing = new ArrayList<>();
        if (isBlank(slots.get("city"))) {
            missing.add("city");
        }
        if (isBlank(slots.get("checkIn"))) {
            missing.add("checkIn");
        }
        if (isBlank(slots.get("checkOut"))) {
            missing.add("checkOut");
        }
        if (!missing.isEmpty()) {
            return collecting(session, missing, askText(missing));
        }

        // ---------- 齐了，去查 ----------
        LocalDate checkIn = LocalDate.parse(slots.get("checkIn"), DATE);
        LocalDate checkOut = LocalDate.parse(slots.get("checkOut"), DATE);
        int roomCount = parseRoomCount(slots.get("roomCount"));
        String bedType = slots.get("bedType");

        List<AgentReplyVO.Candidate> found = searchTool.search(
                slots.get("city"), checkIn, checkOut, roomCount, bedType);

        if (found.isEmpty()) {
            session.setDraftJson(null);
            return AgentReplyVO.builder()
                    .sessionId(session.getSessionId())
                    .intent(session.getIntent())
                    .stage("NO_RESULT")
                    .slots(session.getSlots())
                    .missing(List.of())
                    .candidates(List.of())
                    .reply("抱歉，" + slots.get("city") + " " + slots.get("checkIn")
                            + " 到 " + slots.get("checkOut") + " 期间没找到可订"
                            + (bedType == null ? "" : "的" + bedType + "房")
                            + "。换个日期、减少间数，或者告诉我别的城市试试？")
                    .build();
        }

        List<AgentReplyVO.Candidate> top = found.size() > 3 ? found.subList(0, 3) : found;
        AgentReplyVO.Candidate best = top.get(0);

        // P4.4 会拿这个草案去确认；P4.3 只存下来
        session.setDraftJson(toJson(best));

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(found.size()).append(" 个可订的房型。");
        sb.append("最便宜的是「").append(best.getHotelName()).append(" / ")
                .append(best.getRoomTypeName()).append("」，")
                .append(best.getNights()).append(" 晚 ")
                .append(best.getRoomCount()).append(" 间共 ¥")
                .append(best.getTotalAmount().stripTrailingZeros().toPlainString()).append("。");
        if (top.size() > 1) {
            sb.append("还有 ");
            for (int i = 1; i < top.size(); i++) {
                AgentReplyVO.Candidate c = top.get(i);
                sb.append("「").append(c.getHotelName()).append("/").append(c.getRoomTypeName())
                        .append(" ¥").append(c.getTotalAmount().stripTrailingZeros().toPlainString())
                        .append("」");
                if (i < top.size() - 1) {
                    sb.append("、");
                }
            }
            sb.append("。");
        }

        return AgentReplyVO.builder()
                .sessionId(session.getSessionId())
                .intent(session.getIntent())
                .stage("SEARCHED")
                .slots(session.getSlots())
                .missing(List.of())
                .candidates(top)
                .reply(sb.toString())
                .build();
    }

    private AgentReplyVO collecting(AgentSession session, List<String> missing, String text) {
        return AgentReplyVO.builder()
                .sessionId(session.getSessionId())
                .intent(session.getIntent())
                .stage("COLLECTING")
                .slots(session.getSlots())
                .missing(missing)
                .candidates(List.of())
                .reply(text)
                .build();
    }

    private AgentReplyVO plain(AgentSession session, String stage, String text) {
        return AgentReplyVO.builder()
                .sessionId(session.getSessionId())
                .intent(session.getIntent())
                .stage(stage)
                .slots(session.getSlots())
                .missing(List.of())
                .candidates(List.of())
                .reply(text)
                .build();
    }

    /** 追问话术（模板，不调模型 —— 省一次调用，也更可控） */
    private String askText(List<String> missing) {
        StringBuilder sb = new StringBuilder("好的。");
        for (String m : missing) {
            switch (m) {
                case "city" -> sb.append("请问在哪个城市？");
                case "checkIn" -> sb.append("什么时候入住（比如 11月20号）？");
                case "checkOut" -> sb.append("住几晚、哪天离店？");
                default -> sb.append("还需要了解 ").append(m).append("。");
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /**
     * 规整日期：格式对不对、是不是过去、离店是否晚于入住
     *
     * @return 有问题时返回一句人话，没问题返回 null
     */
    private String normalizeDates(Map<String, String> slots) {
        String in = slots.get("checkIn");
        String out = slots.get("checkOut");
        if (isBlank(in) && isBlank(out)) {
            return null;
        }
        LocalDate checkIn;
        LocalDate checkOut;
        try {
            if (!isBlank(in)) {
                checkIn = LocalDate.parse(in, DATE);
            } else {
                return null;
            }
            if (!isBlank(out)) {
                checkOut = LocalDate.parse(out, DATE);
            } else {
                return null;
            }
        } catch (Exception e) {
            return "日期格式不对";
        }
        slots.put("checkIn", checkIn.format(DATE));
        slots.put("checkOut", checkOut.format(DATE));

        if (checkIn.isBefore(LocalDate.now())) {
            return "入住日期不能是过去";
        }
        if (!checkOut.isAfter(checkIn)) {
            return "离店日期必须晚于入住";
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static int parseRoomCount(String s) {
        if (isBlank(s)) {
            return 1;
        }
        try {
            int n = Integer.parseInt(s.trim());
            return Math.max(1, Math.min(n, 5));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void appendHistory(AgentSession session, String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        session.getHistory().add(m);
        // 只留最近 10 轮，避免会话越滚越大
        while (session.getHistory().size() > 20) {
            session.getHistory().remove(0);
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
