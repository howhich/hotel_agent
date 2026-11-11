-- =============================================================================
--  P4 迁移：给已有库补上登录用户
--
--  已经有库的人执行这一份即可（新建库直接跑 seed-p0.sql 就包含了）。
--  ⚠️ 重复执行是安全的（ON DUPLICATE KEY UPDATE）。
--
--  两个账号的密码都是：123456
--    9001 testuser   USER   主测试账号
--    9002 otheruser  USER   用来验证越权
-- =============================================================================

USE `hotel_booking`;

INSERT INTO `sys_user`
    (`user_id`, `username`, `email`, `password`, `nickname`, `role`, `status`, `is_deleted`)
VALUES
    (9001, 'testuser', 'test@hotel.local',
     '$2a$10$39N7jElLb.YpkbwgWfTG/.sZx9qYOX7qYcZnKBOgbJr.a21i3gQfG', '测试用户', 'USER', 1, 0),
    (9002, 'otheruser', 'other@hotel.local',
     '$2a$10$5.VpoSXmsgSIHDbtWfT.aeKb3LU3ENappKPxMkJoRVHr5V1dS7YvW', '另一个用户', 'USER', 1, 0)
ON DUPLICATE KEY UPDATE
    `password`   = VALUES(`password`),
    `nickname`   = VALUES(`nickname`),
    `status`     = VALUES(`status`),
    `is_deleted` = VALUES(`is_deleted`);

SELECT `user_id`, `username`, `role`, `status`,
       LEFT(`password`, 7) AS `hash_prefix`
FROM `sys_user`
ORDER BY `user_id`;
