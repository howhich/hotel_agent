package com.fancy.hotel.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 按天价格日历
 *
 * <p>总价 = Σ(入住区间内每天的价格) × 间数 —— 不是「均价 × 晚数」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("room_price")
public class RoomPrice {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roomTypeId;
    private LocalDate stayDate;

    /** 挂牌价（单间/晚） */
    private BigDecimal price;

    /** 会员价（可空） */
    private BigDecimal memberPrice;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 实际使用价：有会员价用会员价 */
    public BigDecimal effectivePrice() {
        return memberPrice != null ? memberPrice : price;
    }
}
