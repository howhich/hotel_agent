package com.fancy.hotel.common;

/**
 * 当前请求的登录用户（P4.1）
 *
 * <p>用 {@link ThreadLocal} 存，因为一次请求从 Controller 到 Service 都在同一个线程上。
 * 拦截器校验 token 成功后 {@link #set}，请求结束时 {@link #clear}。
 *
 * <h3>★ 为什么必须 clear</h3>
 * Tomcat 的线程是<b>池化复用</b>的。如果不清，下一个请求会被"上一个人的身份"污染 ——
 * 这是最经典的越权漏洞来源之一（A 的请求跑完，B 的请求复用了同一个线程，
 * 读到的还是 A 的 userId）。所以 clear 放在 {@code afterCompletion} 里，
 * 无论请求成功还是抛异常都会执行。
 *
 * <h3>为什么 Service 要"从上下文取"而不是"接收参数"</h3>
 * 参数是可以被调用方随便传的。只要 userId 还能从请求体里带进来，
 * 越权就永远堵不住。**身份只能来自凭证，不能来自入参。**
 */
public final class UserContext {

    private UserContext() {
    }

    /** 当前登录用户 */
    public record CurrentUser(Long userId, String username, String role) {
    }

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /** 取当前用户；未登录返回 null */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 取当前用户 ID，未登录就抛 401
     *
     * <p>业务代码要的就是这个：拿不到身份就别继续。
     */
    public static Long requireUserId() {
        CurrentUser u = HOLDER.get();
        if (u == null || u.userId() == null) {
            throw BusinessException.unauthorized("未登录或登录已过期");
        }
        return u.userId();
    }

    /** 当前用户是否管理员/客服（这两类可以看别人的订单） */
    public static boolean isPrivileged() {
        CurrentUser u = HOLDER.get();
        if (u == null || u.role() == null) {
            return false;
        }
        String r = u.role().toUpperCase();
        return "ADMIN".equals(r) || "SUPPORT".equals(r);
    }

    /** 当前角色名（未登录返回 null） */
    public static String role() {
        CurrentUser u = HOLDER.get();
        return u == null ? null : u.role();
    }
}
