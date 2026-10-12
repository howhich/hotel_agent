package com.fancy.hotel.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户常用地点（"家"/"公司"，供 Agent 解析"我家附近的酒店"）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user_poi")
public class SysUserPoi {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 标签：家/公司/学校 */
    private String poiTag;

    private String poiName;
    private String poiAddress;

    private BigDecimal longitude;
    private BigDecimal latitude;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
