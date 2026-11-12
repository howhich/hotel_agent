package com.fancy.hotel.domain.dto;

import lombok.Data;

/** Agent 对话入参（P4.3） */
@Data
public class AgentChatDTO {

    /**
     * 会话 id；第一次对话不传，服务端生成并返回，之后带着它继续
     */
    private String sessionId;

    /** 用户说的话 */
    private String message;

    /**
     * 路由模式（仅供 A/B 评测与排查，生产可以不传）
     *
     * <pre>
     *   null / "router"  模型输出 {意图, 槽位}，代码分派（默认）
     *   "tools"          真 function calling：模型自己选工具 + 填参数
     * </pre>
     */
    private String mode;
}
