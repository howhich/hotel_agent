package com.fancy.hotel.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ★ 按天库存（本项目最核心的表）
 *
 * <p>一行 = 一个房型的某一天。{@code UNIQUE(room_type_id, stay_date)} 保证一天只有一行。
 * <p>「能不能订」= 区间内每一天都满足 {@code soldRooms + 需求 <= totalRooms}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("room_inventory")
public class RoomInventory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roomTypeId;

    /** 入住日（区间左闭右开的每一天） */
    private LocalDate stayDate;

    /** 当日总房量（冗余自 room_type） */
    private Integer totalRooms;

    /** 当日已售间数 */
    private Integer soldRooms;

    /** 乐观锁版本号 */
    private Integer version;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 剩余可售 = total - sold（非数据库字段，便于展示） */
    public int remain() {
        return (totalRooms == null ? 0 : totalRooms) - (soldRooms == null ? 0 : soldRooms);
    }
}
