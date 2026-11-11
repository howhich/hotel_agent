package com.fancy.hotel.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器注册
 *
 * <p>两个拦截器，职责分开、顺序有讲究：
 * <ol>
 *   <li>{@link AuthInterceptor}（全站）—— <b>你是谁</b>。校验 token，把用户放进 {@code UserContext}</li>
 *   <li>{@link AdminInterceptor}（只是运维路径）—— <b>你能不能</b>。要求 ADMIN/SUPPORT</li>
 * </ol>
 * 注册顺序就是执行顺序，所以 Auth 一定先跑（否则 Admin 判断时拿不到用户）。
 *
 * <h3>★ 运维接口的路径清单就是权限边界</h3>
 * 下面这串就是"需要管理员"的全部接口。新增运维接口时必须往这里加 ——
 * 否则它会变成"任何登录用户都能调"，而且不会有任何报错提醒你。
 * 约定：运维/调试接口统一放在这些前缀下面。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AdminInterceptor adminInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ① 全站要登录，只放行"登录"本身
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",       // 登录本身当然不能要求先登录
                        "/error"             // Spring 的错误转发
                )
                .order(1);

        // ② 运维接口要管理员
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns(
                        "/booking/inv/**",            // 库存缓存：rebuild / compare / dump
                        "/booking/maintenance/**",    // 清理：stat / cleanup
                        "/booking/delay/**",          // 延迟队列：stat / rebuild / sweep
                        "/booking/hotel/geo/**",      // GEO 索引：rebuild / stat
                        "/booking/hotel/price/**",    // 改价
                        "/booking/expire-scan"        // 手动批量释放超时订单
                )
                .order(2);
    }
}
