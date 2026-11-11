package com.fancy.hotel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.hotel.common.Result;
import com.fancy.hotel.common.UserContext;
import com.fancy.hotel.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录校验拦截器（P4.1）
 *
 * <p>流程：
 * <pre>
 *   读 Authorization: Bearer &lt;token&gt;
 *     → AuthService.resolve() 校验
 *     → 成功：UserContext.set(...)，请求继续
 *     → 失败：直接写 401，请求终止
 *   请求结束（无论成功还是抛异常）：UserContext.clear()
 * </pre>
 *
 * <h3>★ 为什么"缺少 token"和"token 无效"都要回 401</h3>
 * 401 的语义就是"你没有有效凭证，请先去登录"。前端拿到 401 应该跳登录页。
 * 如果回 400（参数错）或 500，前端就不知道该怎么办了。
 *
 * <h3>★ afterCompletion 里清上下文</h3>
 * Tomcat 的线程是池化复用的，不清就会被下一个请求"继承"上一个人的身份 ——
 * 这是最经典的越权漏洞来源之一。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // 浏览器跨域预检不带 token，必须放过
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = extractToken(request);
        if (token == null) {
            write401(response, "缺少 Authorization: Bearer <token>");
            return false;
        }

        UserContext.CurrentUser user = authService.resolve(token);
        if (user == null) {
            write401(response, "登录已过期，请重新登录");
            return false;
        }

        UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // ★ 必须清，否则线程复用会串身份
        UserContext.clear();
    }

    /**
     * 从 {@code Authorization: Bearer xxx} 里取出 token
     *
     * <p>同时兼容直接写 token（不带 Bearer 前缀）的情况，便于用 curl/脚本调试。
     */
    public static String extractToken(HttpServletRequest request) {
        String h = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (h == null || h.isBlank()) {
            return null;
        }
        h = h.trim();
        if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            h = h.substring(7).trim();
        }
        return h.isEmpty() ? null : h;
    }

    private void write401(HttpServletResponse response, String message) throws Exception {
        log.debug("拦截未登录请求: {}", message);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                objectMapper.writeValueAsString(Result.fail(401, message)));
        // 显式 flush：拦截器返回 false 之后，Spring 不会再走正常的响应处理流程，
        // 早点把缓冲刷出去，免得不同容器/客户端组合下拿到"有 Content-Length 但读不到内容"。
        response.flushBuffer();
    }
}
