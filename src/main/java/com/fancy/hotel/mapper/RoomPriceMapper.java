package com.fancy.hotel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.hotel.domain.entity.RoomPrice;
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
}
