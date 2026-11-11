package com.fancy.hotel.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册登录拦截器（P4.1）
 *
 * <p>默认<b>全站都要登录</b>，只放行白名单。白名单只该有"登录"这种
 * 必须在未登录状态下访问的接口 —— 千万不要图省事把业务接口加进来。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",       // 登录本身当然不能要求先登录
                        "/error"             // Spring 的错误转发
                );
    }
}
