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
-- 测试用户
--
--   P4.1 起有真正的登录了，密码是 BCrypt 密文（不是占位符）。
--   两个账号的密码都是：123456
--
--     9001 testuser   USER   主测试账号
--     9002 otheruser  USER   用来验证越权（A 不能看 B 的订单）
--
--   ★ 密码用 ON DUPLICATE KEY UPDATE 覆盖，所以重复执行这份种子会
--     把密码重置回 123456 —— 本地/测试环境这样最省事。
--   ★ 这是**演示账号**，真实环境当然不能这么干。
-- -----------------------------------------------------------------------------
INSERT INTO `sys_user`
    (`user_id`, `username`, `email`, `password`, `nickname`, `role`, `status`, `is_deleted`)
VALUES
    (9001, 'testuser', 'test@hotel.local',
     '$2a$10$39N7jElLb.YpkbwgWfTG/.sZx9qYOX7qYcZnKBOgbJr.a21i3gQfG', '测试用户', 'USER', 1, 0),
    (9002, 'otheruser', 'other@hotel.local',
     '$2a$10$5.VpoSXmsgSIHDbtWfT.aeKb3LU3ENappKPxMkJoRVHr5V1dS7YvW', '另一个用户', 'USER', 1, 0),
    (9003, 'admin', 'admin@hotel.local',
     '$2a$10$39N7jElLb.YpkbwgWfTG/.sZx9qYOX7qYcZnKBOgbJr.a21i3gQfG', '管理员', 'ADMIN', 1, 0)
ON DUPLICATE KEY UPDATE
    `password`   = VALUES(`password`),
    `nickname`   = VALUES(`nickname`),
    `status`     = VALUES(`status`),
    `is_deleted` = VALUES(`is_deleted`);

-- -----------------------------------------------------------------------------
-- 酒店（杭州，坐标都是真实西湖周边的近似值）
--
--   P2.2 的 GEO 半径查询需要「多家酒店 + 已知坐标」，否则没法验证距离对不对。
--   以 1001 (120.1400, 30.2489) 为原点，几家的直线距离大致是：
--     1002  ~1.4 km     1003  ~2.9 km     1004  ~7.1 km     1005  ~7.0 km
--   所以用 1km / 3km / 8km 三档半径就能测出"哪些进、哪些不进"。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `hotel`
    (`hotel_id`, `name`, `city`, `address`, `longitude`, `latitude`, `star`, `score`, `min_price`, `status`, `is_deleted`)
VALUES
    (1001, '杭州西湖畔精选酒店', '杭州', '杭州市西湖区南山路 100 号',
     120.1400000, 30.2489000, 5, 4.8, 400.00, 1, 0),
    (1002, '杭州西湖国宾馆', '杭州', '杭州市西湖区杨公堤 18 号',
     120.1300000, 30.2400000, 5, 4.9, 300.00, 1, 0),
    (1003, '杭州湖滨银泰酒店', '杭州', '杭州市上城区延安路 258 号',
     120.1700000, 30.2500000, 4, 4.5, 400.00, 1, 0),
    (1004, '杭州西溪湿地民宿', '杭州', '杭州市西湖区西溪路 666 号',
     120.0700000, 30.2700000, 3, 4.2, 250.00, 1, 0),
    (1005, '杭州钱江新城酒店', '杭州', '杭州市上城区富春路 701 号',
     120.2100000, 30.2300000, 5, 4.7, 500.00, 1, 0);

-- -----------------------------------------------------------------------------
-- 房型
--   RT 2001 大床房：总 3 间，免费取消           -> 用来演示"超卖"（3 间抢第 4 间）
--   RT 2002 双床房：总 2 间，限时免费（阶梯退款）-> 用来演示阶梯退款
--   RT 2003~2006：给 P2.2 的附近酒店用，价格档位各不相同（便于 P2.3 验证价格排序）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `room_type`
    (`room_type_id`, `hotel_id`, `name`, `total_rooms`, `capacity`, `bed_type`, `area_sqm`,
     `with_breakfast`, `has_window`, `cancel_policy`, `status`, `is_deleted`)
VALUES
    (2001, 1001, '高级大床房', 3, 2, '1.8m 大床', 32, 1, 1, 1, 1, 0),
    (2002, 1001, '豪华双床房', 2, 2, '两张 1.2m 床', 38, 0, 1, 2, 1, 0),
    (2003, 1002, '园景大床房', 2, 2, '1.8m 大床', 35, 1, 1, 1, 1, 0),
    (2004, 1003, '商务双床房', 3, 2, '两张 1.2m 床', 30, 0, 1, 2, 1, 0),
    (2005, 1004, '溪畔木屋', 2, 2, '1.8m 大床', 28, 1, 1, 1, 1, 0),
    (2006, 1005, '江景套房', 2, 3, '2m 大床 + 沙发床', 55, 1, 1, 3, 1, 0);

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

-- 2003~2006：价格档位各不相同，方便 P2.3 验证"按价格排序"
--
-- ★ 注意这里把 room_type_id 放进递归 CTE，而不是用 CROSS JOIN 再关联：
--   `FROM ... CROSS JOIN (...) rt ON DUPLICATE KEY UPDATE ...` 里的 ON
--   会被 MySQL 当成 JOIN 的 ON 条件解析，直接语法错误。
--   把房型作为递归的一列带下去，就完全避开了 JOIN。
INSERT INTO `room_price` (`room_type_id`, `stay_date`, `price`, `member_price`)
WITH RECURSIVE seed(room_type_id, n) AS (
    SELECT rt.room_type_id, 0
    FROM `room_type` rt
    WHERE rt.room_type_id IN (2003, 2004, 2005, 2006)
    UNION ALL
    SELECT room_type_id, n + 1 FROM seed WHERE n < 179
)
SELECT seed.room_type_id,
       DATE_ADD(CURDATE(), INTERVAL seed.n DAY),
       CASE seed.room_type_id
            WHEN 2003 THEN CASE WHEN DAYOFWEEK(DATE_ADD(CURDATE(), INTERVAL seed.n DAY)) IN (1, 7)
                                THEN 450.00 ELSE 300.00 END
            WHEN 2004 THEN CASE WHEN DAYOFWEEK(DATE_ADD(CURDATE(), INTERVAL seed.n DAY)) IN (1, 7)
                                THEN 600.00 ELSE 400.00 END
            WHEN 2005 THEN CASE WHEN DAYOFWEEK(DATE_ADD(CURDATE(), INTERVAL seed.n DAY)) IN (1, 7)
                                THEN 380.00 ELSE 250.00 END
            WHEN 2006 THEN CASE WHEN DAYOFWEEK(DATE_ADD(CURDATE(), INTERVAL seed.n DAY)) IN (1, 7)
                                THEN 750.00 ELSE 500.00 END
       END,
       NULL
FROM seed
ON DUPLICATE KEY UPDATE `price` = VALUES(`price`);

-- -----------------------------------------------------------------------------
-- 校验
-- -----------------------------------------------------------------------------
SELECT '酒店' AS 项, COUNT(*) AS 条数 FROM `hotel`
UNION ALL SELECT '房型', COUNT(*) FROM `room_type`
UNION ALL SELECT '价格日历', COUNT(*) FROM `room_price`
UNION ALL SELECT '测试用户', COUNT(*) FROM `sys_user`;
