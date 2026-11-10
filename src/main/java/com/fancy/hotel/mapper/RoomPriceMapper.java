package com.fancy.hotel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.hotel.domain.entity.RoomPrice;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 按天价格日历
 */
public interface RoomPriceMapper extends BaseMapper<RoomPrice> {

    /** 查区间价格：[from, to) */
    @Select("SELECT * FROM room_price "
            + "WHERE room_type_id = #{roomTypeId} AND stay_date >= #{from} AND stay_date < #{to} "
            + "ORDER BY stay_date")
    List<RoomPrice> selectRange(@Param("roomTypeId") Long roomTypeId,
                                @Param("from") LocalDate from,
                                @Param("to") LocalDate to);

    /**
     * 写入/更新某一天的价格（P3.4 的改价接口用）
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 一条语句搞定"有就改、没有就插"。
     *
     * <p>★ 注意不要写成"先 INSERT IGNORE 再 UPDATE"：那会给已存在的行留下共享锁，
     * 同事务里再 UPDATE 同一行就成了 S→X 升级死锁（P3.2 踩过）。
     */
    @Insert("INSERT INTO room_price (room_type_id, stay_date, price, member_price) "
            + "VALUES (#{roomTypeId}, #{stayDate}, #{price}, NULL) "
            + "ON DUPLICATE KEY UPDATE price = VALUES(price), update_time = NOW()")
    int upsertPrice(@Param("roomTypeId") Long roomTypeId,
                    @Param("stayDate") LocalDate stayDate,
                    @Param("price") java.math.BigDecimal price);
}
