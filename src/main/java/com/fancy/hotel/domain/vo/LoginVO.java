package com.fancy.hotel.domain.vo;

import lombok.Builder;
import lombok.Data;

/** 登录结果（P4.1） */
@Data
@Builder
public class LoginVO {

    /** 后续请求放到 {@code Authorization: Bearer <token>} 里 */
    private String token;

    private Long userId;
    private String username;
    private String nickname;
    private String role;

    /** token 有效期（秒） */
    private Long expiresInSeconds;
}
