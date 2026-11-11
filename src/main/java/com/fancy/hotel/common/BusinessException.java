package com.fancy.hotel.common;

import lombok.Getter;

/**
 * 业务异常
 *
 * <p>{@code code} 直接就是 HTTP 状态码，便于 GlobalExceptionHandler 原样返回。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    // ---------- 常用快捷构造 ----------

    /** 400 参数/状态不合法 */
    public static BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }

    /** 404 资源不存在 */
    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    /** 409 冲突（库存不足、重复提交、状态已变化） */
    public static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }

    /** 401 未登录 / 登录已过期 */
    public static BusinessException unauthorized(String message) {
        return new BusinessException(401, message);
    }

    /** 403 登录了，但无权访问这个资源（越权） */
    public static BusinessException forbidden(String message) {
        return new BusinessException(403, message);
    }
}
