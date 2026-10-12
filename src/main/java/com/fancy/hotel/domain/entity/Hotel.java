package com.fancy.hotel.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 酒店 / 民宿
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("hotel")
public class Hotel {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务ID（雪花） */
    private Long hotelId;

    private String name;
    private String city;
    private String address;

    /** 经度（GEO 索引用，GEO 约定先经度后纬度） */
    private BigDecimal longitude;

    /** 纬度 */
    private BigDecimal latitude;

    /** 星级 1-5 */
    private Integer star;

    /** 评分 0.0-5.0 */
    private BigDecimal score;

    /** 最低价（冗余，列表排序用） */
    private BigDecimal minPrice;

    /** 1-上架 0-下架 */
    private Integer status;

    @TableLogic
    private Integer isDeleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
