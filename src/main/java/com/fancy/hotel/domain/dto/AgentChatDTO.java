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
}
