package com.fancy.hotel.controller;

import com.fancy.hotel.common.Result;
import com.fancy.hotel.common.UserContext;
import com.fancy.hotel.config.AuthInterceptor;
import com.fancy.hotel.domain.dto.LoginDTO;
import com.fancy.hotel.domain.entity.SysUser;
import com.fancy.hotel.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录相关（P4.1）
 *
 * <pre>
 *   POST /auth/login    登录，拿 token（唯一不需要 token 的接口）
 *   POST /auth/logout   退出，token 立刻失效
 *   GET  /auth/me       看当前登录的是谁
 * </pre>
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(authService.login(dto));
    }

    @PostMapping("/logout")
    public Result logout(HttpServletRequest request) {
        String token = AuthInterceptor.extractToken(request);
        boolean ok = authService.logout(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loggedOut", ok);
        return Result.ok(out);
    }

    @GetMapping("/me")
    public Result me() {
        Long userId = UserContext.requireUserId();
        SysUser u = authService.findByUserId(userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("role", UserContext.role());
        out.put("username", u == null ? null : u.getUsername());
        out.put("nickname", u == null ? null : u.getNickname());
        return Result.ok(out);
    }
}
