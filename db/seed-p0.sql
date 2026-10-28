-- =============================================================================
--  P0 测试数据（可重复执行）
--
--  用途：让 P0 的下单/支付/取消链路能立刻跑起来。
--  数据：1 家酒店 + 2 个房型 + 从今天起 60 天的价格日历 + 1 个测试用户
--
--  执行方式（本地经隧道 13306）：
--      mysql -h 127.0.0.1 -P 13306 -u root -p hotel_booking < seed-p0.sql
-- =============================================================================

USE `hotel_booking`;

-- -----------------------------------------------------------------------------
-- 测试用户（P0 无鉴权，password 只是占位）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sys_user`
    (`user_id`, `username`, `email`, `password`, `nickname`, `role`, `status`, `is_deleted`)
VALUES
    (9001, 'testuser', 'test@hotel.local',
     '$2a$10$placeholderplaceholderplaceholderplaceholderplaceholder', '测试用户', 'USER', 1, 0);

-- -----------------------------------------------------------------------------
-- 酒店（杭州西湖附近）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `hotel`
    (`hotel_id`, `name`, `city`, `address`, `longitude`, `latitude`, `star`, `score`, `min_price`, `status`, `is_deleted`)
VALUES
    (1001, '杭州西湖畔精选酒店', '杭州', '杭州市西湖区南山路 100 号',
     120.1400000, 30.2489000, 5, 4.8, 400.00, 1, 0);

-- -----------------------------------------------------------------------------
-- 房型
--   RT 2001 大床房：总 3 间，免费取消           -> 用来演示"超卖"（3 间抢第 4 间）
--   RT 2002 双床房：总 2 间，限时免费（阶梯退款）-> 用来演示阶梯退款
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `room_type`
    (`room_type_id`, `hotel_id`, `name`, `total_rooms`, `capacity`, `bed_type`, `area_sqm`,
     `with_breakfast`, `has_window`, `cancel_policy`, `status`, `is_deleted`)
VALUES
    (2001, 1001, '高级大床房', 3, 2, '1.8m 大床', 32, 1, 1, 1, 1, 0),
    (2002, 1001, '豪华双床房', 2, 2, '两张 1.2m 床', 38, 0, 1, 2, 1, 0);

-- -----------------------------------------------------------------------------
-- 价格日历：从【今天】起 180 天（可重复执行，会按当前日期顺延）
--   周一~周四 400/500，周五~周日 600/700（演示"逐晚累加"与周末差价）
--   MySQL 的 DAYOFWEEK：1=周日，7=周六
--
--   ★ 用 CURDATE() 而不是写死日期：种子数据要"过很久还能用"。
--     180 天是给测试脚本留余量（它们把日期锚定到"下一个周一"再往后推最多 57 天）。
-- -----------------------------------------------------------------------------
INSERT INTO `room_price` (`room_type_id`, `stay_date`, `price`, `member_price`)
WITH RECURSIVE seq(n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 179
)
SELECT 2001,
       DATE_ADD(CURDATE(), INTERVAL n DAY),
       CASE WHEN DAYOFWEEK(DATE_ADD(CURDATE(), INTERVAL n DAY)) IN (1, 7)
            THEN 600.00 ELSE 400.00 END,
       NULL
FROM seq
ON DUPLICATE KEY UPDATE `price` = VALUES(`price`);

INSERT INTO `room_price` (`room_type_id`, `stay_date`, `price`, `member_price`)
WITH RECURSIVE seq(n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 179
)
SELECT 2002,
       DATE_ADD(CURDATE(), INTERVAL n DAY),
       CASE WHEN DAYOFWEEK(DATE_ADD(CURDATE(), INTERVAL n DAY)) IN (1, 7)
            THEN 700.00 ELSE 500.00 END,
       NULL
FROM seq
ON DUPLICATE KEY UPDATE `price` = VALUES(`price`);

-- -----------------------------------------------------------------------------
-- 校验
-- -----------------------------------------------------------------------------
SELECT '酒店' AS 项, COUNT(*) AS 条数 FROM `hotel` WHERE `hotel_id` = 1001
UNION ALL SELECT '房型', COUNT(*) FROM `room_type` WHERE `hotel_id` = 1001
UNION ALL SELECT '价格日历(2001)', COUNT(*) FROM `room_price` WHERE `room_type_id` = 2001
UNION ALL SELECT '价格日历(2002)', COUNT(*) FROM `room_price` WHERE `room_type_id` = 2002
UNION ALL SELECT '测试用户', COUNT(*) FROM `sys_user` WHERE `user_id` = 9001;
