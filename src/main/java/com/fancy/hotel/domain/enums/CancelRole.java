package com.fancy.hotel.domain.enums;

import lombok.Getter;

/**
 * 取消方
 */
@Getter
public enum CancelRole {

    USER(1, "用户"),
    SUPPORT(2, "客服"),
    SYSTEM(3, "系统");

    private final int code;
    private final String desc;

    CancelRole(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static CancelRole fromCode(Integer code) {
        if (code == null) {
            return USER;
        }
        for (CancelRole r : values()) {
            if (r.code == code) {
                return r;
            }
        }
        throw new IllegalArgumentException("非法的取消方: " + code);
    }
}
