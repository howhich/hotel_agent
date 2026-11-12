package com.fancy.hotel.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.hotel.common.BusinessException;
import com.fancy.hotel.common.UserContext;
import com.fancy.hotel.domain.agent.AgentDraft;
import com.fancy.hotel.domain.agent.AgentSession;
import com.fancy.hotel.domain.dto.CancelBookingDTO;
import com.fancy.hotel.domain.dto.CreateBookingDTO;
import com.fancy.hotel.domain.dto.RescheduleBookingDTO;
import com.fancy.hotel.domain.vo.BookingVO;
import com.fancy.hotel.domain.vo.CancelResultVO;
import com.fancy.hotel.domain.vo.RescheduleResultVO;
import com.fancy.hotel.domain.dto.AgentChatDTO;
import com.fancy.hotel.domain.vo.AgentReplyVO;
import com.fancy.hotel.service.BookingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final MyBookingTool myBookingTool;
    private final BookingService bookingService;
    private final ObjectMapper objectMapper;
    /** 草案多久之后就不算数了（分钟） */
    private final int draftTtlMinutes;
    /** 拼提示时最多带几轮历史（一轮 = 用户+助手两条） */
    private final int maxHistoryTurns;

    public AgentService(LlmClient llmClient,
                        AgentSessionStore sessionStore,
                        HotelSearchTool searchTool,
                        MyBookingTool myBookingTool,
                        BookingService bookingService,
                        ObjectMapper objectMapper,
                        @Value("${hotel.agent.draft-ttl-minutes:5}") int draftTtlMinutes,
                        @Value("${hotel.agent.max-history-turns:10}") int maxHistoryTurns) {
        this.llmClient = llmClient;
        this.sessionStore = sessionStore;
        this.searchTool = searchTool;
        this.myBookingTool = myBookingTool;
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
        this.draftTtlMinutes = Math.max(1, draftTtlMinutes);
        this.maxHistoryTurns = Math.max(1, maxHistoryTurns);
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

        // ---------- ★ 兜底一：日期被塞错槽位 ----------
        //   提示词里同时有 checkIn/checkOut 和 newCheckIn/newCheckOut，
        //   模型偶尔会把预订日期填到 new* 里，导致明明说清了日期却还在追问。
        //   预订/查询时 new* 一律当作 checkIn/checkOut 用。
        normalizeDatesByIntent(session);

        // ---------- ★ 兜底二：明显是"确认"的一句话 ----------
        //   模型看到上文全是"取消/改期"，会把"确认"也判成 CANCEL，于是每次都回
        //   "要取消 X 吗？"，用户永远按不下去。要不要动钱这件事不能只靠模型。
        String effectiveIntent = router.intent;
        if (session.selectedDraft() != null && looksLikeConfirm(session, message)) {
            effectiveIntent = "CONFIRM";
            log.debug("「{}」当作 CONFIRM 处理（当前有待确认草案: {}）",
                    message, session.selectedDraft().getKind());
        }
        session.setIntent(effectiveIntent);

        appendHistory(session, "user", message);

        // ---------- ②' 用户是否从候选里挑了一个 ----------
        //   "要第二个""就国宾馆那家" —— 不重新查库，只把选中项挪到 selectedIndex
        int picked = resolvePick(router.pick, session.getCandidates());
        // ★ 先落选中项，但【只有不打算下单时才停在这里】。
        //   否则会出现死循环：用户选完 [2] 说"确认"，模型又把 pick 填成 2
        //   （因为上文一直在提候选），于是每次都回"确认吗？"，永远下不了单。
        //   优先级：明确说"确认" > 挑一个候选。
        if (picked >= 0) {
            session.setSelectedIndex(picked);
        }
        if (picked >= 0 && !"CONFIRM".equals(effectiveIntent)) {
            AgentDraft d = session.getCandidates().get(picked);
            String pickedText = confirmPrompt(d, picked);
            AgentReplyVO selectedReply = AgentReplyVO.builder()
                    .sessionId(session.getSessionId())
                    .intent(session.getIntent())
                    .stage("SELECTED")
                    .slots(session.getSlots())
                    .missing(List.of())
                    .candidates(List.of())
                    .reply(pickedText)
                    .build();
            appendHistory(session, "assistant", pickedText);
            sessionStore.save(session);
            return selectedReply;
        }

        // ---------- ③④ 按意图分派 ----------
        AgentReplyVO reply;
        switch (effectiveIntent) {
            case "BOOK", "QUERY" -> reply = handleSearch(session);
            case "CONFIRM" -> reply = handleConfirm(session);
            case "MYBOOKINGS" -> reply = handleMyBookings(session);
            case "CANCEL" -> reply = handleCancel(session);
            case "RESCHEDULE" -> reply = handleReschedule(session);
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
                + "- intent: BOOK(要订房) / QUERY(只是想查) / CANCEL(取消) / RESCHEDULE(改期)\n-         CONFIRM(确认下单：\\n"
                + "- slots.city: 城市名，如 \"杭州\"；没有就 null\n"
                + "- slots.checkIn: \"YYYY-MM-DD\"；相对日期按今天换算；没有就 null\n"
                + "- slots.checkOut: \"YYYY-MM-DD\"；\"住两晚\" => 离店 = 入住 + 2 天；没有就 null\n"
                + "- slots.roomCount: 整数；没有就 null\n"
                + "- slots.bedType: \"大床\" 或 \"双床\"；没有就 null\n"
                + "- 打招呼、问\"你能干什么\"、闲聊 -> intent 一律填 CHITCHAT（不要填 QUERY）\n"
                + "- pick: 只有在用户【明确从上一条回复列出的候选中挑一个】时才填：\n"
                + "        填那个酒店的完整名字（照抄候选里的写法），或序号 \"1\"/\"2\"/\"3\"；\n"
                + "        其它情况一律 null\n"
                + "- intent 还可以是 MYBOOKINGS（\"我的订单\"\"我订了什么\"\"查下我的订单\"）\n"
                + "- slots.bookingId: 用户提到的订单号（纯数字串）；没有就 null\n"
                + "- slots.newCheckIn / slots.newCheckOut: 【只有改期(RESCHEDULE)才填】新的入住/离店日期；\n"
                + "        预订和查询一律填在 checkIn/checkOut 里，不要填这两个\n"
                + "示例输出：{\"intent\":\"BOOK\",\"slots\":{\"city\":\"杭州\",\"checkIn\":\""
                + today.plusDays(9).format(DATE) + "\",\"checkOut\":\""
                + today.plusDays(11).format(DATE)
                + "\",\"roomCount\":1,\"bedType\":\"大床\"}}";
    }

    private RouterResult route(AgentSession session, String message) {
        String payload = buildRoutePayload(session, message);
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
            String pick = root.path("pick").asText("").trim();
            if ("null".equalsIgnoreCase(pick)) {
                pick = "";
            }

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
            return new RouterResult(intent, slots, pick);
        } catch (Exception e) {
            // 模型偶尔会返回坏 JSON。不猜，直接当闲聊回一句引导的话。
            log.warn("模型 JSON 解析失败，降级为 CHITCHAT: {} / {}", e.getMessage(), json);
            return new RouterResult("CHITCHAT", Map.of(), "");
        }
    }

    private record RouterResult(String intent, Map<String, String> slots, String pick) {
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
            session.clearDraft();
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

        // ★ 整份候选都存进会话（不再只留最便宜那一个）：
        //   回复里已经把 2~3 个选项列给用户了，用户说"要第二个/那家 X"必须有处可选。
        //   同时这也是"下单内容的唯一来源" —— 确认时用它，不用用户那句话、不用模型。
        List<AgentDraft> drafts = new ArrayList<>();
        long draftedAt = System.currentTimeMillis();
        for (AgentReplyVO.Candidate c : top) {
            drafts.add(AgentDraft.builder()
                    .hotelId(c.getHotelId())
                    .hotelName(c.getHotelName())
                    .roomTypeId(c.getRoomTypeId())
                    .roomTypeName(c.getRoomTypeName())
                    .checkIn(checkIn)
                    .checkOut(checkOut)
                    .roomCount(roomCount)
                    .totalAmount(c.getTotalAmount())
                    .createdAt(draftedAt)
                    .build());
        }
        session.setCandidates(drafts);
        session.setSelectedIndex(0);

        // ★ 带编号：这样用户说"第二个"才有明确所指（原来只是一段自然语言列举）
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(found.size()).append(" 个可订的房型：\n");
        for (int i = 0; i < top.size(); i++) {
            AgentReplyVO.Candidate c = top.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append(c.getHotelName()).append(" / ").append(c.getRoomTypeName())
                    .append("　").append(c.getNights()).append(" 晚 ")
                    .append(c.getRoomCount()).append(" 间　¥")
                    .append(c.getTotalAmount() == null ? "?"
                            : c.getTotalAmount().stripTrailingZeros().toPlainString());
            if (i == 0) {
                sb.append("　（最便宜）");
            }
            sb.append("\n");
        }
        sb.append("说「1」「2」或直接说酒店名可以换一家；说「确认」就订选中的这个。");

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

    // =========================================================================
    // ★ 第 ⑤ 步：确认下单（HITL —— Human In The Loop）
    // =========================================================================

    /**
     * 用户确认后真正下单
     *
     * <h3>★ 三条安全线</h3>
     * <ol>
     *   <li><b>下单内容只来自草案</b>，不来自用户这句话、也不来自模型。
     *       否则"订那个 100 块的"就能把订单引到别处去。</li>
     *   <li><b>身份只来自登录上下文</b>（{@link UserContext}），模型永远不参与。</li>
     *   <li><b>草案用完即作废</b>，避免连点两次"确认"下两单。
     *       （还有一层网：{@code @Idempotent} 会让重放的请求拿回同一个订单号）</li>
     * </ol>
     *
     * <h3>为什么"房没了"回 200 而不是 409</h3>
     * 用户这一轮对话是成功的，只是没订上。把 409 抛给前端会让它以为接口出错了；
     * 正确做法是让 Agent 说人话："这个房型刚被订走了，我再帮你查？"
     */
    private AgentReplyVO handleConfirm(AgentSession session) {
        AgentDraft draft = session.selectedDraft();
        if (draft == null) {
            return plain(session, "NO_DRAFT",
                    "我这边没有待确认的方案。先说想订哪里的房，我查给你看。");
        }

        // ---------- 过期检查：草案是"某一刻的房态 + 价格" ----------
        long ageMs = System.currentTimeMillis() - draft.getCreatedAt();
        if (ageMs > draftTtlMinutes * 60_000L) {
            session.clearDraft();
            long mins = Math.max(1, ageMs / 60_000L);
            return plain(session, "DRAFT_EXPIRED",
                    "那个方案是 " + mins + " 分钟前查的，价格和房态可能变了，我不敢直接下单。"
                            + "要我重新查一遍吗？");
        }

                // ---------- 三种动作共用同一套安全线：草案 -> 身份 -> 执行 ----------
        //   身份在 execute* 里从 UserContext 取；内容只来自草案。
        String kind = draft.getKind() == null ? "BOOK" : draft.getKind();
        log.info("Agent 确认执行: kind={}, userId={}, bookingId={}, roomTypeId={}",
                kind, UserContext.requireUserId(), draft.getBookingId(), draft.getRoomTypeId());
        try {
            return switch (kind) {
                case "CANCEL" -> executeCancel(session, draft);
                case "RESCHEDULE" -> executeReschedule(session, draft);
                default -> executeBook(session, draft);
            };
        } catch (BusinessException e) {
            // 房没了 / 已被取消 / 不是你的 —— 草案作废，让用户重新来
            session.clearDraft();
            log.warn("Agent 确认执行失败: kind={}, err={}", kind, e.getMessage());
            return plain(session, "ACTION_FAILED",
                    "没办成：" + e.getMessage() + "。要我重新查一下吗？");
        }
    }

    // =========================================================================
    // 拼提示 / 解析用户挑选
    // =========================================================================

    /**
     * 拼出"给模型看的那段话"：最近几轮对话 + 已知槽位 + 当前这句
     *
     * <p>★ history 原来是<b>只写不读</b>：会话里存了 20 条对话，但发给模型的
     * 只有槽位和当前这句。后果是"再住一晚呢""往后推一天"这类依赖上文的说法
     * 解析不出来 —— 模型看不到刚才聊到哪了。
     *
     * <p>只带最近 {@code maxHistoryTurns} 轮（一轮 = 用户 + 助手）：全量发既费钱，
     * 越长的上下文也越容易跑偏。
     */
    private String buildRoutePayload(AgentSession session, String message) {
        StringBuilder sb = new StringBuilder();
        List<Map<String, String>> h = session.getHistory();
        if (h != null && !h.isEmpty()) {
            int from = Math.max(0, h.size() - maxHistoryTurns * 2);
            sb.append("最近的对话（旧→新）：\n");
            for (int i = from; i < h.size(); i++) {
                Map<String, String> m = h.get(i);
                if (m == null) {
                    continue;
                }
                String role = "assistant".equals(m.get("role")) ? "助手" : "用户";
                sb.append(role).append("：").append(m.get("content")).append('\n');
            }
            sb.append('\n');
        }
        sb.append("已知信息：").append(toJson(session.getSlots())).append('\n');
        sb.append("用户这句话：").append(message);
        return sb.toString();
    }

    /**
     * 把模型给的 pick 解析成候选下标
     *
     * <p>支持三种写法：序号（"2"）、中文序号（"第二个"）、酒店/房型名（"国宾馆"）。
     * 解析不出来就返回 -1 —— 当作"用户没挑"，继续走正常分派，绝不硬猜。
     */
    private int resolvePick(String pick, List<AgentDraft> candidates) {
        if (isBlank(pick) || candidates == null || candidates.isEmpty()) {
            return -1;
        }
        String p = pick.trim();

        // 纯数字："2"
        if (p.matches("\\d+")) {
            return toIndex(Integer.parseInt(p), candidates.size());
        }
        // 中文序号："第二个" / "第二家" / "第 2 个"
        String cn = p.replaceAll("[第个家条项\\s]", "");
        Map<String, Integer> cnNum = Map.of("一", 1, "二", 2, "两", 2, "三", 3);
        if (cnNum.containsKey(cn)) {
            return toIndex(cnNum.get(cn), candidates.size());
        }
        if (cn.matches("\\d+")) {
            return toIndex(Integer.parseInt(cn), candidates.size());
        }
        // 酒店名 / 房型名：双向包含，容错"杭州西湖国宾馆" vs "国宾馆"
        for (int i = 0; i < candidates.size(); i++) {
            AgentDraft d = candidates.get(i);
            String hotel = d.getHotelName() == null ? "" : d.getHotelName();
            String room = d.getRoomTypeName() == null ? "" : d.getRoomTypeName();
            if ((!hotel.isEmpty() && (p.contains(hotel) || hotel.contains(p)))
                    || (!room.isEmpty() && (p.contains(room) || room.contains(p)))) {
                return i;
            }
        }
        return -1;
    }

    private static int toIndex(int oneBased, int size) {
        return (oneBased >= 1 && oneBased <= size) ? oneBased - 1 : -1;
    }
    // =========================================================================
    // 三个执行动作（P2）：预订 / 取消 / 改期
    //
    // ★ 三个方法形状完全一致：读草案 -> 从 UserContext 取身份 -> 调既有服务 -> 清草案。
    //   取消和改期【不在这里重算退款/差价】—— 那是业务服务的事，
    //   Agent 再算一遍迟早会和主链路不一致。
    // =========================================================================

    private AgentReplyVO executeBook(AgentSession session, AgentDraft draft) {
        Long userId = UserContext.requireUserId();
        CreateBookingDTO dto = new CreateBookingDTO();
        dto.setUserId(userId);          // 只用于幂等指纹；createBooking 内部仍然以 UserContext 为准
        dto.setHotelId(draft.getHotelId());
        dto.setRoomTypeId(draft.getRoomTypeId());
        dto.setCheckIn(draft.getCheckIn());
        dto.setCheckOut(draft.getCheckOut());
        dto.setRoomCount(draft.getRoomCount());
        dto.setGuestCount(1);

        Long bookingId = bookingService.createBooking(dto);
        session.clearDraft();
        session.getSlots().clear();     // 这一单结束了，用户可以接着说下一单
        return AgentReplyVO.builder()
                .sessionId(session.getSessionId())
                .intent(session.getIntent())
                .stage("BOOKED")
                .slots(session.getSlots())
                .missing(List.of())
                .candidates(List.of())
                .bookingId(bookingId)
                .reply("订好了！订单号 " + bookingId + "。"
                        + nz(draft.getHotelName()) + " / " + nz(draft.getRoomTypeName()) + "，"
                        + draft.getCheckIn() + " 到 " + draft.getCheckOut() + "，"
                        + draft.getRoomCount() + " 间，共 ¥" + money(draft.getTotalAmount())
                        + "。请在 15 分钟内完成支付，超时会自动释放房间。")
                .build();
    }

    private AgentReplyVO executeCancel(AgentSession session, AgentDraft draft) {
        CancelBookingDTO dto = new CancelBookingDTO();
        dto.setReason("用户通过预订助手取消");
        dto.setCancelRole(1);           // 1 = 用户主动取消，走退订政策
        CancelResultVO r = bookingService.cancelBooking(draft.getBookingId(), dto);
        session.clearDraft();
        session.getSlots().clear();
        return AgentReplyVO.builder()
                .sessionId(session.getSessionId())
                .intent(session.getIntent())
                .stage("CANCELLED")
                .slots(session.getSlots())
                .missing(List.of())
                .candidates(List.of())
                .bookingId(r.getBookingId())
                .reply("已取消订单 " + r.getBookingId() + "。"
                        + "已付 ¥" + money(r.getPaidAmount()) + "，"
                        + "退款 ¥" + money(r.getRefundAmount())
                        + (r.getRefundRatioText() == null ? "" : "（" + r.getRefundRatioText() + "）")
                        + "，退订政策：" + nz(r.getPolicyName()) + "。")
                .build();
    }

    private AgentReplyVO executeReschedule(AgentSession session, AgentDraft draft) {
        RescheduleBookingDTO dto = new RescheduleBookingDTO();
        dto.setNewCheckIn(draft.getNewCheckIn());
        dto.setNewCheckOut(draft.getNewCheckOut());
        RescheduleResultVO r = bookingService.rescheduleBooking(draft.getBookingId(), dto);
        session.clearDraft();
        session.getSlots().clear();
        String diffSign = (r.getPriceDiff() != null && r.getPriceDiff().signum() >= 0) ? "+" : "";
        return AgentReplyVO.builder()
                .sessionId(session.getSessionId())
                .intent(session.getIntent())
                .stage("RESCHEDULED")
                .slots(session.getSlots())
                .missing(List.of())
                .candidates(List.of())
                .bookingId(r.getBookingId())
                .reply("改好了！订单 " + r.getBookingId() + " 从 "
                        + r.getOldCheckIn() + "~" + r.getOldCheckOut()
                        + " 改到 " + r.getNewCheckIn() + "~" + r.getNewCheckOut() + "，"
                        + r.getOldNights() + " 晚 -> " + r.getNewNights() + " 晚，"
                        + "金额 ¥" + money(r.getOldTotalAmount()) + " -> ¥" + money(r.getNewTotalAmount())
                        + "（差价 " + diffSign + money(r.getPriceDiff()) + "）。")
                .build();
    }

    // =========================================================================
    // 我的订单 / 取消 / 改期（P2）
    // =========================================================================

    /**
     * 列出当前用户能操作的订单，并把它们存成候选
     *
     * <p>存成候选是为了复用 P1 的"挑选"机制：用户说"第二个"就能直接选中要处理的那一单，
     * 不用再让他去复制订单号。
     */
    private AgentReplyVO handleMyBookings(AgentSession session) {
        List<BookingVO> list = myBookingTool.activeBookings(UserContext.requireUserId());
        if (list.isEmpty()) {
            session.clearDraft();
            return plain(session, "NO_RESULT", "你当前没有待支付或已确认的订单。");
        }
        List<AgentDraft> drafts = new ArrayList<>();
        for (BookingVO v : list) {
            drafts.add(toBookingDraft(v));
        }
        session.setCandidates(drafts);
        session.setSelectedIndex(0);

        StringBuilder sb = new StringBuilder("你有 " + list.size() + " 个可操作的订单：\n");
        for (int i = 0; i < list.size(); i++) {
            BookingVO v = list.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append(nz(v.getHotelName())).append(" / ").append(nz(v.getRoomTypeName()))
                    .append("　").append(v.getCheckIn()).append(" 到 ").append(v.getCheckOut())
                    .append("　").append(nz(v.getStatusDesc()))
                    .append("　¥").append(money(v.getTotalAmount()))
                    .append("　订单号 ").append(v.getBookingId()).append('\n');
        }
        sb.append("要取消哪一单？说「1」「2」或酒店名。");
        return AgentReplyVO.builder()
                .sessionId(session.getSessionId())
                .intent(session.getIntent())
                .stage("LISTED")
                .slots(session.getSlots())
                .missing(List.of())
                .candidates(List.of())
                .reply(sb.toString())
                .build();
    }

    private AgentReplyVO handleCancel(AgentSession session) {
        BookingVO target = resolveBooking(session);
        if (target == null) {
            return promptWhichBooking(session);
        }
        session.setCandidates(new ArrayList<>(List.of(toBookingDraft(target))));
        session.setSelectedIndex(0);
        return AgentReplyVO.builder()
                .sessionId(session.getSessionId())
                .intent(session.getIntent())
                .stage("CONFIRM_NEEDED")
                .slots(session.getSlots())
                .missing(List.of())
                .candidates(List.of())
                .reply("要取消「" + nz(target.getHotelName()) + " / " + nz(target.getRoomTypeName())
                        + "」（" + target.getCheckIn() + " 到 " + target.getCheckOut()
                        + "，订单号 " + target.getBookingId() + "）吗？\n"
                        + "退款按退订政策计算，取消后我会告诉你具体金额。说「确认」我就办。")
                .build();
    }

    private AgentReplyVO handleReschedule(AgentSession session) {
        BookingVO target = resolveBooking(session);
        if (target == null) {
            return promptWhichBooking(session);
        }
        Map<String, String> slots = session.getSlots();
        String newIn = slots.get("newCheckIn");
        String newOut = slots.get("newCheckOut");
        if (isBlank(newIn) || isBlank(newOut)) {
            return collecting(session, List.of("newCheckIn", "newCheckOut"),
                    "要改到哪几天？说一下新的入住和离店日期（比如「12月1号住两晚」）。");
        }
        LocalDate ni;
        LocalDate no;
        try {
            ni = LocalDate.parse(newIn.trim(), DATE);
            no = LocalDate.parse(newOut.trim(), DATE);
        } catch (Exception e) {
            return collecting(session, List.of("newCheckIn", "newCheckOut"),
                    "新日期我没理解对，麻烦再说一次（比如「12月1号住两晚」）。");
        }
        if (ni.isBefore(LocalDate.now()) || !no.isAfter(ni)) {
            return collecting(session, List.of("newCheckIn", "newCheckOut"),
                    "新日期不合法（不能是过去，离店必须晚于入住），麻烦再说一次。");
        }

        AgentDraft draft = toBookingDraft(target);
        draft.setKind("RESCHEDULE");
        draft.setNewCheckIn(ni);
        draft.setNewCheckOut(no);
        session.setCandidates(new ArrayList<>(List.of(draft)));
        session.setSelectedIndex(0);

        return AgentReplyVO.builder()
                .sessionId(session.getSessionId())
                .intent(session.getIntent())
                .stage("CONFIRM_NEEDED")
                .slots(session.getSlots())
                .missing(List.of())
                .candidates(List.of())
                .reply("把订单 " + target.getBookingId() + "（" + nz(target.getHotelName()) + " / "
                        + nz(target.getRoomTypeName()) + "）从 "
                        + target.getCheckIn() + "~" + target.getCheckOut()
                        + " 改到 " + ni + "~" + no + " 吗？\n"
                        + "金额会按新日期重算，改完告诉你差价。说「确认」我就办。")
                .build();
    }

    /**
     * 从订单号槽位里找出目标订单；没说、或对不上就返回 null
     *
     * <p>只有一单时默认选它 —— 但仍然会要一次确认，不会直接执行。
     */
    private BookingVO resolveBooking(AgentSession session) {
        List<BookingVO> active = myBookingTool.activeBookings(UserContext.requireUserId());
        if (active.isEmpty()) {
            return null;
        }
        String bid = session.getSlots().get("bookingId");
        if (!isBlank(bid)) {
            for (BookingVO v : active) {
                if (String.valueOf(v.getBookingId()).equals(bid.trim())) {
                    return v;
                }
            }
            return null;
        }
        return active.size() == 1 ? active.get(0) : null;
    }

    /** 没说清是哪一单时，把可操作的订单列出来让他挑 */
    private AgentReplyVO promptWhichBooking(AgentSession session) {
        AgentReplyVO listed = handleMyBookings(session);
        listed.setReply(listed.getReply()
                + "\n（也可以直接把订单号告诉我，那样更快。）");
        return listed;
    }

    /** 把一笔订单包成"取消"草案（后续可改成改期） */
    private AgentDraft toBookingDraft(BookingVO v) {
        return AgentDraft.builder()
                .kind("CANCEL")
                .bookingId(v.getBookingId())
                .hotelId(v.getHotelId())
                .hotelName(v.getHotelName())
                .roomTypeId(v.getRoomTypeId())
                .roomTypeName(v.getRoomTypeName())
                .checkIn(v.getCheckIn())
                .checkOut(v.getCheckOut())
                .roomCount(v.getRoomCount())
                .totalAmount(v.getTotalAmount())
                .createdAt(System.currentTimeMillis())
                .build();
    }

    /** 确认话术按动作类型变：订 / 取消 / 改期 */
    private String confirmPrompt(AgentDraft d, int index) {
        String kind = d.getKind() == null ? "BOOK" : d.getKind();
        String what = "[" + (index + 1) + "]「" + nz(d.getHotelName()) + " / "
                + nz(d.getRoomTypeName()) + "」";
        return switch (kind) {
            case "CANCEL" -> "好的，选的是 " + what + "（订单号 " + d.getBookingId()
                    + "）。确认取消这一单吗？";
            case "RESCHEDULE" -> "好的，选的是 " + what + "（订单号 " + d.getBookingId()
                    + "）。确认按新日期改期吗？";
            default -> "好的，选的是 " + what + "，" + d.getCheckIn() + " 到 " + d.getCheckOut()
                    + "，" + d.getRoomCount() + " 间共 ¥" + money(d.getTotalAmount())
                    + "。确认订这个吗？";
        };
    }

    /** 预订/查询时把误填到 newCheckIn/newCheckOut 的日期挪回 checkIn/checkOut */
    private void normalizeDatesByIntent(AgentSession session) {
        Map<String, String> slots = session.getSlots();
        String intent = session.getIntent();
        boolean bookish = "BOOK".equals(intent) || "QUERY".equals(intent);
        if (bookish && isBlank(slots.get("checkIn")) && !isBlank(slots.get("newCheckIn"))) {
            slots.put("checkIn", slots.get("newCheckIn"));
            if (!isBlank(slots.get("newCheckOut"))) {
                slots.put("checkOut", slots.get("newCheckOut"));
            }
            slots.remove("newCheckIn");
            slots.remove("newCheckOut");
            log.debug("日期被模型放到了 new* 槽位，已挪回 checkIn/checkOut");
        }
    }

    /**
     * 这句话是不是在"确认"
     *
     * <p>只在【已经有待确认草案】时才用，且只看很短的句子（长句子交给模型）。
     * 这是对模型的一道兜底：要不要动钱，不能完全指望它的意图判断。
     */
    private boolean looksLikeConfirm(AgentSession session, String msg) {
        if (msg == null) {
            return false;
        }
        String m = msg.replaceAll("[\\s，。！!？?、~,.]", "");
        if (m.isEmpty() || m.length() > 6) {
            return false;
        }
        AgentDraft pending = session.selectedDraft();
        String kind = pending == null || pending.getKind() == null ? "BOOK" : pending.getKind();
        // 上下文是"要取消吗"，用户回"取消吧"当然算确认
        if ("CANCEL".equals(kind) && m.contains("取消")) {
            return true;
        }
        if ("RESCHEDULE".equals(kind) && m.contains("改")) {
            return true;
        }
        return m.contains("确认") || m.contains("确定") || m.contains("就它") || m.contains("就这个")
                || m.equals("是的") || m.equals("对") || m.equals("好的") || m.equals("可以")
                || m.equalsIgnoreCase("ok") || m.equalsIgnoreCase("yes");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String money(java.math.BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
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
