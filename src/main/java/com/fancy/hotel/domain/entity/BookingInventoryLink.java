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
 * ★ 预订占用日期台账
 *
 * <p>记录「这一单到底占了哪几天」。释放库存时按它逐天还原，不用重算区间；
 * 也支持部分取消与对账（查某一天被哪些订单占了）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("booking_inventory_link")
public class BookingInventoryLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bookingId;
    private Long roomTypeId;

    /** 占用的某一天 */
    private LocalDate stayDate;

    /** 该天占用的间数 */
    private Integer roomCount;

    /** 1-已释放（取消/失效时置位） */
    private Integer released;

    private LocalDateTime releaseTime;
    private LocalDateTime createTime;
}
