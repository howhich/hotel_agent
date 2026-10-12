package com.fancy.hotel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.hotel.domain.entity.BookingInventoryLink;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * ★ 预订占用日期台账
 */
public interface BookingInventoryLinkMapper extends BaseMapper<BookingInventoryLink> {

    /** 查该单尚未释放的占用记录（释放时逐条还原） */
    @Select("SELECT * FROM booking_inventory_link "
            + "WHERE booking_id = #{bookingId} AND released = 0 ORDER BY stay_date")
    List<BookingInventoryLink> selectUnreleased(@Param("bookingId") Long bookingId);

    /**
     * 标记某条占用为已释放
     *
     * <p>{@code released = 0} 作为前置条件，避免重复释放把库存加回去两次。
     *
     * @return 1 = 本次标记成功；0 = 已被释放过
     */
    @Update("UPDATE booking_inventory_link SET released = 1, release_time = NOW() "
            + "WHERE id = #{id} AND released = 0")
    int markReleased(@Param("id") Long id);

    /**
     * 按「订单 + 日期」抢占式标记释放（改期用：只释放差集里的那几天）
     *
     * @return 1 = 本次标记成功；0 = 不存在或已被释放
     */
    @Update("UPDATE booking_inventory_link SET released = 1, release_time = NOW() "
            + "WHERE booking_id = #{bookingId} AND stay_date = #{stayDate} AND released = 0")
    int markReleasedByDate(@Param("bookingId") Long bookingId,
                           @Param("stayDate") LocalDate stayDate);

    /**
     * ★ 认领某天的占用（改期时新增的天）
     *
     * <p>为什么用 upsert 而不是 insert：
     * 表上有 {@code UNIQUE(booking_id, stay_date)}，而一个订单可能<b>改回来</b>
     * （比如 10/12→10/15 改到 10/20→10/22 再改回来）。那时 10/12~10/14 的台账行
     * 还在（只是 released=1），直接 insert 会撞唯一键。
     *
     * <p>所以这里把 released 翻回 0 —— 这是安全的：那几天的库存是"先减后加"，
     * 净效果为零，不会重复计数。
     */
    @Insert("INSERT INTO booking_inventory_link "
            + "(booking_id, room_type_id, stay_date, room_count, released, create_time) "
            + "VALUES (#{bookingId}, #{roomTypeId}, #{stayDate}, #{roomCount}, 0, NOW()) AS new "
            + "ON DUPLICATE KEY UPDATE released = 0, release_time = NULL, room_count = new.room_count")
    int upsertHold(@Param("bookingId") Long bookingId,
                   @Param("roomTypeId") Long roomTypeId,
                   @Param("stayDate") LocalDate stayDate,
                   @Param("roomCount") Integer roomCount);
}
