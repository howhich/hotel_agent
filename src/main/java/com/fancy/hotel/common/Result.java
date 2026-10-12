package com.fancy.hotel.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体
 *
 * <p>注意：与 TaxiAgent 不同，本项目<b>让 HTTP 状态码与业务码保持一致</b>
 * （失败时返回 4xx/5xx，而不是一律 200）。理由是 TaxiAgent 那种
 * "HTTP 200 + body 里 code=500" 的写法会让调用方必须解析 body 才知道成败，
 * 我们自己的验证脚本就曾因此把失败误判成成功。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {

    private int code;
    private String message;
    private Object data;

    public static Result ok() {
        return new Result(200, "success", null);
    }

    public static Result ok(Object data) {
        return new Result(200, "success", data);
    }

    public static Result fail(int code, String message) {
        return new Result(code, message, null);
    }
}
