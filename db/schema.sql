-- =============================================================================
--  hotel-booking  数据库结构 —— 【P0 阶段】
--  MySQL 8.0 / utf8mb4 / utf8mb4_unicode_ci
--
--  部署位置: 服务器 43.142.27.106 的 taxi-mysql 容器
--            本地经 SSH 隧道访问  ->  127.0.0.1:13306
--
--  重新初始化（**会删掉全部数据**）:
--      DROP DATABASE IF EXISTS hotel_booking;
--      然后重新执行本文件
--
--  ★ 标记的表是本项目的核心设计点，说明见 docs/01-domain-model.md
--
--  本文件只包含 P0 的 8 张表。后续阶段的表在对应阶段再追加，避免提前冻结结构:
--      P1 幂等         -> idempotent_record
--      P4 Agent 会话   -> sys_chat / sys_messages / sys_chat_tool_response
--      P5 工单 + RAG   -> ticket / ticket_chat / qa_info / qa_es
--
--  已确认的设计决定:
--      1. 入住区间【左闭右开】: 占用 [check_in, check_out)
--      2. 状态码区分详细: 90 已取消(用户/客服) / 91 已失效(系统超时)
--      3. 幂等用【独立表】(P1 实现)，不在 booking 上加冗余字段
--      4. 取消政策先用【枚举硬编码】，但下单时把规则快照到订单
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `hotel_booking`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `hotel_booking`;


-- =============================================================================
-- 1. 酒店 / 民宿
-- =============================================================================
CREATE TABLE `hotel` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `hotel_id`    BIGINT        NOT NULL                COMMENT '业务ID(雪花)',
  `name`        VARCHAR(128)  NOT NULL                COMMENT '酒店名称',
  `city`        VARCHAR(64)   NOT NULL                COMMENT '城市',
  `address`     VARCHAR(255)  NOT NULL                COMMENT '详细地址',
  `longitude`   DECIMAL(10,7) NOT NULL                COMMENT '经度(GEO 索引用)',
  `latitude`    DECIMAL(10,7) NOT NULL                COMMENT '纬度(GEO 索引用)',
  `star`        TINYINT       NOT NULL DEFAULT 3      COMMENT '星级 1-5',
  `score`       DECIMAL(3,1)  NOT NULL DEFAULT 5.0    COMMENT '评分 0.0-5.0',
  `min_price`   DECIMAL(10,2) DEFAULT NULL            COMMENT '最低价(冗余,列表排序用)',
  `status`      TINYINT       NOT NULL DEFAULT 1      COMMENT '1-上架 0-下架',
  `is_deleted`  TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hotel_id` (`hotel_id`),
  KEY `idx_city_status` (`city`, `status`),
  KEY `idx_score` (`score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='酒店/民宿';


-- =============================================================================
-- 2. 房型
--    total_rooms = 这个房型一共几间，是库存上限；
--    真正的「按天可售量」在 room_inventory。
--    cancel_policy 1免费取消 2限时免费 3不可取消 —— 阶梯规则在 Java 枚举里硬编码。
-- =============================================================================
CREATE TABLE `room_type` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `room_type_id`   BIGINT       NOT NULL            COMMENT '业务ID(雪花)',
  `hotel_id`       BIGINT       NOT NULL            COMMENT '所属酒店',
  `name`           VARCHAR(64)  NOT NULL            COMMENT '大床房/双床房/套房',
  `total_rooms`    INT          NOT NULL            COMMENT '该房型总房量',
  `capacity`       TINYINT      NOT NULL DEFAULT 2  COMMENT '可住人数',
  `bed_type`       VARCHAR(32)  DEFAULT NULL        COMMENT '床型描述',
  `area_sqm`       INT          DEFAULT NULL        COMMENT '面积(平米)',
  `with_breakfast` TINYINT(1)   NOT NULL DEFAULT 0  COMMENT '是否含早',
  `has_window`     TINYINT(1)   NOT NULL DEFAULT 1  COMMENT '是否有窗',
  `cancel_policy`  TINYINT      NOT NULL DEFAULT 1  COMMENT '取消政策: 1免费取消 2限时免费 3不可取消',
  `status`         TINYINT      NOT NULL DEFAULT 1  COMMENT '1-在售 0-停售',
  `is_deleted`     TINYINT(1)   NOT NULL DEFAULT 0,
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_type_id` (`room_type_id`),
  KEY `idx_hotel` (`hotel_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房型';


-- =============================================================================
-- 3. ★ 按天库存（本项目最核心的表）
--
--   核心思想: 把「区间库存」拆成「每一天一行」。
--     - 一次预订占用 [check_in, check_out) 的每一天
--     - 「能不能订」退化成: 区间内每一天都满足 sold_rooms + 需求间数 <= total_rooms
--     - UNIQUE(room_type_id, stay_date) 保证一天只有一行，是并发扣减的基础
--
--   为什么不「一行管一个区间」:
--     那样判断重叠要扫描该房型的所有历史区间，热点下性能极差且持续恶化。
-- =============================================================================
CREATE TABLE `room_inventory` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `room_type_id` BIGINT   NOT NULL              COMMENT '房型ID',
  `stay_date`    DATE     NOT NULL              COMMENT '入住日(区间左闭右开的每一天)',
  `total_rooms`  INT      NOT NULL              COMMENT '当日总房量(冗余自 room_type)',
  `sold_rooms`   INT      NOT NULL DEFAULT 0    COMMENT '当日已售间数',
  `version`      INT      NOT NULL DEFAULT 0    COMMENT '乐观锁版本号',
  `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_roomtype_date` (`room_type_id`, `stay_date`),
  KEY `idx_date` (`stay_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='按天库存';


-- =============================================================================
-- 4. 按天价格日历
--    总价 = Σ(入住区间内每天的价格) × 间数
--    注意不是「一晚均价 × 晚数」—— 周末/工作日价格不同，先算均价会有舍入误差。
-- =============================================================================
CREATE TABLE `room_price` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `room_type_id` BIGINT        NOT NULL         COMMENT '房型ID',
  `stay_date`    DATE          NOT NULL         COMMENT '日期',
  `price`        DECIMAL(10,2) NOT NULL         COMMENT '挂牌价(单间/晚)',
  `member_price` DECIMAL(10,2) DEFAULT NULL     COMMENT '会员价(可空)',
  `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_roomtype_date` (`room_type_id`, `stay_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='按天价格日历';


-- =============================================================================
-- 5. 预订单
--
--   ★ check_in 含、check_out 不含（左闭右开）。
--     写错会导致每晚多占一天 —— 酒店库存最容易出错的地方。
--
--   ★ cancel_policy_snapshot: 下单那一刻把取消规则冻结在订单上。
--     否则运营改了政策，老订单会被追溯适用新规则，用户投诉「我下单时不是这么说的」。
-- =============================================================================
CREATE TABLE `booking` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `booking_id`    BIGINT        NOT NULL           COMMENT '业务ID(雪花)',
  `user_id`       BIGINT        NOT NULL           COMMENT '下单用户',
  `hotel_id`      BIGINT        NOT NULL,
  `room_type_id`  BIGINT        NOT NULL,

  -- ===== 入住区间（左闭右开）=====
  `check_in`      DATE          NOT NULL           COMMENT '入住日(含)',
  `check_out`     DATE          NOT NULL           COMMENT '离店日(不含)',
  `nights`        INT           NOT NULL           COMMENT '晚数 = check_out - check_in',
  `room_count`    INT           NOT NULL DEFAULT 1 COMMENT '预订间数',
  `guest_count`   INT           NOT NULL DEFAULT 1 COMMENT '入住人数',
  `with_breakfast` TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否含早',

  -- ===== 金额 =====
  `total_amount`  DECIMAL(10,2) NOT NULL           COMMENT '总价(逐晚累加)',
  `paid_amount`   DECIMAL(10,2) DEFAULT NULL       COMMENT '实付金额',
  `refund_amount` DECIMAL(10,2) DEFAULT NULL       COMMENT '已退金额',

  -- ===== 状态 =====
  `booking_status` TINYINT      NOT NULL DEFAULT 10 COMMENT '10待支付 20已确认 30已入住 40已完成 90已取消 91已失效',
  `cancel_role`    TINYINT      DEFAULT NULL        COMMENT '取消方: 1用户 2客服 3系统',
  `cancel_reason`  VARCHAR(255) DEFAULT NULL        COMMENT '取消原因',
  `cancel_policy_snapshot` VARCHAR(512) DEFAULT NULL COMMENT '下单时的取消政策快照(JSON: 政策码+阶梯规则)',

  -- ===== 时间轴 =====
  `create_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `pay_time`       DATETIME DEFAULT NULL            COMMENT '支付时间',
  `check_in_time`  DATETIME DEFAULT NULL            COMMENT '实际入住时间',
  `check_out_time` DATETIME DEFAULT NULL            COMMENT '实际离店时间',
  `cancel_time`    DATETIME DEFAULT NULL            COMMENT '取消/失效时间',
  `update_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  `is_deleted`     TINYINT(1) NOT NULL DEFAULT 0    COMMENT '逻辑删除',

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_booking_id` (`booking_id`),
  KEY `idx_user_create` (`user_id`, `create_time`),
  KEY `idx_status_update` (`booking_status`, `update_time`),
  KEY `idx_hotel` (`hotel_id`),
  KEY `idx_roomtype` (`room_type_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预订单';


-- =============================================================================
-- 6. ★ 预订占用的日期台账
--
--   为什么需要单独一张表:
--     1) 释放库存时能「精确还原」，不用重算区间
--     2) 支持部分取消（订 3 晚，取消后 2 晚）
--     3) 对账 —— 能查出「某一天被哪些订单占了」，排查超卖时是唯一线索
--
--   与 Redis 预占的关系: Redis 里的区间库存是副本，这张表是 DB 侧的台账。
-- =============================================================================
CREATE TABLE `booking_inventory_link` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `booking_id`   BIGINT   NOT NULL              COMMENT '预订单业务ID',
  `room_type_id` BIGINT   NOT NULL,
  `stay_date`    DATE     NOT NULL              COMMENT '占用的某一天',
  `room_count`   INT      NOT NULL              COMMENT '该天占用的间数',
  `released`     TINYINT(1) NOT NULL DEFAULT 0  COMMENT '1-已释放(取消/失效时)',
  `release_time` DATETIME DEFAULT NULL          COMMENT '释放时间',
  `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_booking_date` (`booking_id`, `stay_date`),
  KEY `idx_roomtype_date` (`room_type_id`, `stay_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预订占用日期台账';


-- =============================================================================
-- 7. 用户
--
--   ★ 相比 TaxiAgent 的 sys_user 补了 3 个唯一索引。
--     它原本一个唯一索引都没有 —— 去重全靠应用层 + 布隆过滤器，
--     而布隆过滤器 rebuild() 是「先 delete 再逐条 add」，重建期查询全部返回未命中。
-- =============================================================================
CREATE TABLE `sys_user` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`         BIGINT       NOT NULL            COMMENT '业务ID(雪花)',
  `username`        VARCHAR(50)  NOT NULL            COMMENT '登录名',
  `email`           VARCHAR(100) DEFAULT NULL        COMMENT '邮箱(可空)',
  `password`        VARCHAR(255) NOT NULL            COMMENT 'BCrypt 密文',
  `phone`           VARCHAR(32)  DEFAULT NULL,
  `nickname`        VARCHAR(64)  DEFAULT NULL,
  `role`            VARCHAR(20)  NOT NULL            COMMENT 'USER/SUPPORT/ADMIN',
  `status`          TINYINT(1)   NOT NULL DEFAULT 1  COMMENT '1启用 0禁用',
  `is_deleted`      TINYINT(1)   NOT NULL DEFAULT 0  COMMENT '逻辑删除',
  `last_login_time` DATETIME     DEFAULT NULL,
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  UNIQUE KEY `uk_username_role` (`username`, `role`),
  UNIQUE KEY `uk_email_role` (`email`, `role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户';


-- =============================================================================
-- 8. 用户常用地点
--    供 Agent 解析「我家附近的酒店」这类表达。
-- =============================================================================
CREATE TABLE `sys_user_poi` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT        NOT NULL,
  `poi_tag`     VARCHAR(64)   NOT NULL          COMMENT '标签: 家/公司/学校',
  `poi_name`    VARCHAR(255)  NOT NULL,
  `poi_address` VARCHAR(500)  DEFAULT NULL,
  `longitude`   DECIMAL(10,7) NOT NULL,
  `latitude`    DECIMAL(10,7) NOT NULL,
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_tag` (`user_id`, `poi_tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户常用地点';


-- =============================================================================
-- 9. 幂等记录（P1）
--
--   两层防御的【第二层】：第一层是 Redis SET NX（快、扛并发），
--   这张表的唯一键在"Redis 挂了"或"请求打到另一台实例"时兜底，
--   同时还能【回放结果】—— 重复请求直接返回首次的 booking_id 而不是报错。
--
--   已有库升级用 db/migration-p1.sql。
-- =============================================================================
CREATE TABLE `idempotent_record` (
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
