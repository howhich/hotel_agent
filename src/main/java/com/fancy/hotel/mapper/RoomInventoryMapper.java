package com.fancy.hotel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.hotel.domain.entity.RoomInventory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * ★ 按天库存 —— 并发扣减的全部 SQL 都在这里
 *
 * <p>用注解式 SQL 而不是 MyBatis-Plus 的 UpdateWrapper，原因是这几条语句依赖
 * {@code sold_rooms + n <= total_rooms} 这种「把条件写进 WHERE」的写法，
 * 手工写更直观，也能保证参数用 {@code #{}} 绑定（不拼字符串）。
 */
public interface RoomInventoryMapper extends BaseMapper<RoomInventory> {

    /**
     * 惰性初始化某天的库存行
     *
     * <p>库存日历不是预先铺满的，用到哪天就建哪天。
     * 靠 {@code UNIQUE(room_type_id, stay_date)} 保证并发下只成功一次。
     */
    @Insert("INSERT IGNORE INTO room_inventory (room_type_id, stay_date, total_rooms, sold_rooms, version) "
            + "VALUES (#{roomTypeId}, #{stayDate}, #{totalRooms}, 0, 0)")
    int insertIgnore(@Param("roomTypeId") Long roomTypeId,
                     @Param("stayDate") LocalDate stayDate,
                     @Param("totalRooms") Integer totalRooms);

    /**
     * ★ 条件扣减（本项目并发的核心）
     *
     * <p>「够不够」和「扣减」在同一条 UPDATE 里完成 —— 数据库行锁保证原子性。
     * <b>影响 0 行就代表这一天不够</b>，调用方据此抛异常让整个事务回滚。
     *
     * @return 影响行数：1 = 扣成功；0 = 该天剩余不足
     */
    @Update("UPDATE room_inventory SET sold_rooms = sold_rooms + #{n}, version = version + 1 "
            + "WHERE room_type_id = #{roomTypeId} AND stay_date = #{stayDate} "
            + "AND sold_rooms + #{n} <= total_rooms")
    int tryOccupy(@Param("roomTypeId") Long roomTypeId,
                  @Param("stayDate") LocalDate stayDate,
                  @Param("n") Integer n);

    /**
     * 释放（取消/失效时按台账逐天还原）
     *
     * <p>{@code sold_rooms >= n} 是防御：正常情况下不会不满足，
     * 但万一台账与库存对不上，宁可释放失败也不要算出负数。
     *
     * @return 影响行数：1 = 释放成功；0 = 数据不一致（会被日志告警）
     */
    @Update("UPDATE room_inventory SET sold_rooms = sold_rooms - #{n}, version = version + 1 "
            + "WHERE room_type_id = #{roomTypeId} AND stay_date = #{stayDate} "
            + "AND sold_rooms >= #{n}")
    int release(@Param("roomTypeId") Long roomTypeId,
                @Param("stayDate") LocalDate stayDate,
                @Param("n") Integer n);

    /**
     * 查区间库存：{@code [from, to)} 左闭右开
     */
    @Select("SELECT * FROM room_inventory "
            + "WHERE room_type_id = #{roomTypeId} AND stay_date >= #{from} AND stay_date < #{to} "
            + "ORDER BY stay_date")
    List<RoomInventory> selectRange(@Param("roomTypeId") Long roomTypeId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);
}
