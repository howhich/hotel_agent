package com.fancy.hotel.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Agent 对话结果（P4.3） */
@Data
@Builder
public class AgentReplyVO {

    /** 会话 id —— 客户端要存下来，下一轮带上 */
    private String sessionId;

    /** 模型判断的意图 */
    private String intent;

    /** 说给用户听的话 */
    private String reply;

    /** 已经收集到的槽位 */
    private Map<String, String> slots;

    /** 还缺哪些（缺的时候 reply 就是在问这些） */
    private List<String> missing;

    /**
     * 当前阶段：
     * <pre>
     *   COLLECTING   还在收集信息（缺槽位）
     *   SEARCHED     查到候选了（P4.4 会在这里出草案让用户确认）
     *   NO_RESULT    查了但没有可订的
     *   UNSUPPORTED  这个意图暂不支持（比如取消 / 改期，留给后面的阶段）
     *   CHITCHAT     闲聊
     * </pre>
     */
    private String stage;

    /** 候选（stage = SEARCHED 时才有） */
    private List<Candidate> candidates;

    /** 真正下单成功后才有：订单号（P4.4） */
    private Long bookingId;

    @Data
    @Builder
    public static class Candidate {
        private Long hotelId;
        private String hotelName;
        private Long roomTypeId;
        private String roomTypeName;
        private Double distanceKm;
        /** 整个入住期间的总价（roomCount 间） */
        private java.math.BigDecimal totalAmount;
        private Integer nights;
        private Integer roomCount;
    }
}
