package com.fancy.hotel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fancy.hotel.common.BusinessException;
import com.fancy.hotel.common.RedisKeyConstants;
import com.fancy.hotel.common.UserContext;
import com.fancy.hotel.domain.dto.LoginDTO;
import com.fancy.hotel.domain.entity.SysUser;
import com.fancy.hotel.domain.vo.LoginVO;
import com.fancy.hotel.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 登录 / 会话（P4.1）
 *
 * <h3>Token 存哪儿</h3>
 * <pre>
 *   key   hotel:token:{32 位随机串}
 *   value userId:role
 *   TTL   默认 2 小时
 * </pre>
 *
 * <h3>★ 登录失败的提示为什么要含糊</h3>
 * "用户不存在" 和 "密码错误" 如果返回两句话，等于给攻击者一个<b>用户名枚举</b>接口：
 * 他可以先用错误的密码把用户名刷一遍，确认哪些是真实存在的账号。
 * 所以两种情况统一回"用户名或密码错误"。
 */
@Slf4j
@Service
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final long tokenTtlSeconds;

    public AuthService(
            SysUserMapper sysUserMapper,
            StringRedisTemplate redis,
            @Value("${hotel.auth.token-ttl-seconds:7200}") long tokenTtlSeconds) {
        this.sysUserMapper = sysUserMapper;
        this.redis = redis;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    /**
     * 登录
     *
     * @throws BusinessException 401 用户名或密码错误；409 账号被禁用
     */
    public LoginVO login(LoginDTO dto) {
        SysUser u = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, dto.getUsername()));

        // ★ 用户不存在 / 密码不对，返回同一句话（防用户名枚举）
        if (u == null || u.getPassword() == null
                || !encoder.matches(dto.getPassword(), u.getPassword())) {
            log.warn("登录失败: username={}", dto.getUsername());
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        if (u.getStatus() == null || u.getStatus() != 1) {
            throw BusinessException.conflict("该账号已被禁用");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        String payload = u.getUserId() + ":" + u.getRole();
        redis.opsForValue().set(RedisKeyConstants.authTokenKey(token), payload,
                Duration.ofSeconds(Math.max(60, tokenTtlSeconds)));

        // 记录最后登录时间（失败不影响登录）
        try {
            sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getUserId, u.getUserId())
                    .set(SysUser::getLastLoginTime, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("更新 last_login_time 失败: {}", e.getMessage());
        }

        log.info("登录成功: userId={}, username={}, role={}", u.getUserId(), u.getUsername(), u.getRole());
        return LoginVO.builder()
                .token(token)
                .userId(u.getUserId())
                .username(u.getUsername())
                .nickname(u.getNickname())
                .role(u.getRole())
                .expiresInSeconds(tokenTtlSeconds)
                .build();
    }

    /**
     * 校验 token，返回当前用户；无效返回 {@code null}
     *
     * <p>这里<b>不抛异常</b>：它被拦截器调用，由拦截器决定"没登录"该怎么回。
     */
    public UserContext.CurrentUser resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String payload;
        try {
            payload = redis.opsForValue().get(RedisKeyConstants.authTokenKey(token));
        } catch (Exception e) {
            // Redis 挂了不能当成"没登录"之外的判断 —— 保守处理：当作无会话
            log.error("校验 token 失败（Redis 不可用）: {}", e.getMessage());
            return null;
        }
        if (payload == null) {
            return null;
        }
        String[] parts = payload.split(":");
        if (parts.length < 2) {
            log.warn("token 内容格式异常: {}", payload);
            return null;
        }
        try {
            return new UserContext.CurrentUser(Long.valueOf(parts[0]), null, parts[1]);
        } catch (NumberFormatException e) {
            log.warn("token 里的 userId 不是数字: {}", parts[0]);
            return null;
        }
    }

    /** 退出登录：删掉 token 立刻失效 */
    public boolean logout(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.delete(RedisKeyConstants.authTokenKey(token)));
        } catch (Exception e) {
            log.warn("删除 token 失败: {}", e.getMessage());
            return false;
        }
    }

    /** 查一个用户（给 /auth/me 补昵称用） */
    public SysUser findByUserId(Long userId) {
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUserId, userId));
    }
}
