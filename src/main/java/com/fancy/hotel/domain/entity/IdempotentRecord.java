package com.fancy.hotel.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 幂等记录
 *
 * <p>这是幂等的<b>第二层</b>防御：第一层是 Redis {@code SET NX}（快，扛并发），
 * 这一层是数据库唯一键（当 Redis 挂了、或请求打到另一台实例时兜底）。
 * 同时它还能<b>回放结果</b> —— 重复请求直接返回首次的 {@code resultRef}，
 * 而不是报错。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("idempotent_record")
public class IdempotentRecord {

    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_DONE = 1;
    public static final int STATUS_FAILED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键：业务类型 + 用户 + 参数指纹 */
    private String idemKey;

    /** 业务类型，如 CREATE_BOOKING */
    private String bizType;

    private Long userId;

    /** 首次执行的结果引用，如 booking_id */
    private String resultRef;

    /** 0 执行中 / 1 已完成 / 2 已失败 */
    private Integer status;

    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
