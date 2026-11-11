package com.fancy.hotel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.hotel.common.Result;
import com.fancy.hotel.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 运维接口的角色门禁（P4.5）
 *
 * <p>这些接口能改价格、重建库存缓存、批量释放订单、清数据 ——
 * 「登录了」远远不够，必须是 <b>ADMIN / SUPPORT</b>。
 *
 * <h3>为什么要单独一个拦截器</h3>
 * {@link AuthInterceptor} 已经在全站跑了（负责"你是谁"），这个只负责"你能不能"。
 * 两个职责分开，读代码时一眼能看出<b>权限边界在哪</b>：
 * {@code WebConfig} 里那串 pathPatterns 就是全部需要管理员的接口，不多不少。
 *
 * <h3>★ 白名单式的思路反过来：运维接口必须登记</h3>
 * 新增一个运维接口时，如果忘了往这里加路径，它就会变成"任何登录用户都能调"。
 * 所以约定是：<b>所有运维/调试接口统一放在这几个前缀下面</b>
 * （/booking/inv、/booking/maintenance、/booking/delay、/booking/hotel/geo、/booking/hotel/price），
 * 才能保证不遗漏。
 *
 * <h3>为什么返回 403 而不是 401</h3>
 * 401 = 你没登录；403 = 你登录了，但没这个权限。前端处理方式不同：
 * 401 跳登录页，403 提示"无权限"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (UserContext.isPrivileged()) {
            return true;
        }
        log.warn("越权拦截（需要管理员）: user={}, role={}, {} {}",
                UserContext.get() == null ? null : UserContext.get().userId(),
                UserContext.role(),
                request.getMethod(), request.getRequestURI());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(403, "该接口需要管理员权限（ADMIN / SUPPORT）")));
        response.flushBuffer();   // 和 AuthInterceptor 同样的理由：早点刷出去
        return false;
    }
}
