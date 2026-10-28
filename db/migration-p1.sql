-- =============================================================================
--  P1 迁移：幂等记录表
--
--  已有库执行本文件即可（CREATE TABLE IF NOT EXISTS，可重复执行）。
--  全新安装直接跑 schema.sql 就行，里面已经包含这张表。
-- =============================================================================

USE `hotel_booking`;

CREATE TABLE IF NOT EXISTS `idempotent_record` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `idem_key`    VARCHAR(128) NOT NULL                COMMENT '幂等键（业务类型 + 用户 + 参数指纹）',
  `biz_type`    VARCHAR(32)  NOT NULL                COMMENT '业务类型，如 CREATE_BOOKING',
  `user_id`     BIGINT       DEFAULT NULL            COMMENT '发起用户（可空）',
  `result_ref`  VARCHAR(64)  DEFAULT NULL            COMMENT '首次执行的结果引用，如 booking_id',
  `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '0执行中 1已完成 2已失败',
  `expire_time` DATETIME     NOT NULL                COMMENT '过期时间（定期清理）',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem_key` (`idem_key`),
  KEY `idx_expire` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='幂等记录';
