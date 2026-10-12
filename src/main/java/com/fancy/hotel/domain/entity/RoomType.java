package com.fancy.hotel.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 房型
 *
 * <p>{@code totalRooms} 是库存上限，真正的按天可售量在 {@code room_inventory}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("room_type")
public class RoomType {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roomTypeId;
    private Long hotelId;

    private String name;
    private Integer totalRooms;
    private Integer capacity;
    private String bedType;
    private Integer areaSqm;
    private Integer withBreakfast;
    private Integer hasWindow;

    /** 取消政策：1免费取消 2限时免费 3不可取消（阶梯规则在 CancelPolicy 枚举里） */
    private Integer cancelPolicy;

    /** 1-在售 0-停售 */
    private Integer status;

    @TableLogic
    private Integer isDeleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
