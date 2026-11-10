package com.fancy.hotel.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理
 *
 * <p>HTTP 状态码与 body 里的 code 保持一致（见 {@link Result} 的注释）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getCode()).body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** @Valid 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return ResponseEntity.badRequest().body(Result.fail(400, msg));
    }

    /**
     * {@code @ModelAttribute}（GET 的 query 参数）校验失败
     *
     * <p>Spring 6 里 {@code MethodArgumentNotValidException extends BindException}，
     * 但反过来不成立：query 参数绑定失败抛的是父类 {@code BindException}，
     * 上面那个处理器<b>接不住</b>，会落到兜底的 500。所以必须单独接一下。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("参数绑定失败: {}", msg);
        return ResponseEntity.badRequest().body(Result.fail(400, msg));
    }

    /**
     * 用错 HTTP 方法（比如对 POST 接口发 GET）
     *
     * <p>Spring 自己会抛 {@code HttpRequestMethodNotSupportedException}，
     * 但如果没人接，它就会掉进下面兜底的 {@code Exception} 处理器变成 <b>500</b> ——
     * 客户端会以为"服务器坏了"，而真实原因只是"你该用 POST"。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("不支持的请求方法: {}（支持 {}）", e.getMethod(), e.getSupportedHttpMethods());
        return ResponseEntity.status(405).body(Result.fail(405,
                "该接口不支持 " + e.getMethod() + "，请用 " + e.getSupportedHttpMethods()));
    }

    /**
     * 请求体读不出来（JSON 语法错、缺字段、类型对不上）
     *
     * <p>同样是"客户端错"，应该是 400 而不是 500。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(400, "请求体格式错误或缺少必要字段"));
    }

    /**
     * 路径变量 / 查询参数类型不对
     *
     * <p>典型场景：{@code GET /booking/availability} 会先命中
     * {@code GET /booking/{bookingId}}，于是把字符串 "availability" 当成订单号去转 Long ——
     * 转换失败抛的就是这个异常。它本该是 <b>400</b>（你传错了），
     * 不接的话会掉进兜底变成 500（看着像服务器坏了）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: {} = {}", e.getName(), e.getValue());
        return ResponseEntity.badRequest().body(Result.fail(400,
                "参数 " + e.getName() + " 的值不合法: " + e.getValue()));
    }

    /**
     * Content-Type 不对（比如 POST 没带 JSON 头）
     *
     * <p>应该是 <b>415</b>，不接就变 500。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result> handleMediaType(HttpMediaTypeNotSupportedException e) {
        log.warn("不支持的 Content-Type: {}（支持 {}）", e.getContentType(), e.getSupportedMediaTypes());
        return ResponseEntity.status(415).body(Result.fail(415,
                "不支持的 Content-Type，请用 application/json"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(400, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(500).body(Result.fail(500, "服务器内部错误"));
    }
}
