-- =============================================================================
--  P2 迁移：订单超时扫表用的索引
--
--  ⚠️ 只跑一次（ALTER TABLE ADD INDEX 重复执行会报 duplicate key name）。
--
--  背景：超时扫表的条件是
--      WHERE booking_status = 10 AND create_time < ?
--  而原来只有 idx_status_update (booking_status, update_time)，
--  create_time 用不上索引，只能先按状态取出来再过滤。
-- =============================================================================

USE `hotel_booking`;

ALTER TABLE `booking` ADD INDEX `idx_status_create` (`booking_status`, `create_time`);
