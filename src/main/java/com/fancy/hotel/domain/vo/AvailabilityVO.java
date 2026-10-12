package com.fancy.hotel.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 可订性查询结果
 */
@Data
@Builder
public class AvailabilityVO {

    private Long roomTypeId;
    private LocalDate checkIn;
    private LocalDate checkOut;
    private Integer nights;
    private Integer roomCount;

    /** 整体是否可订：区间内每一天都满足 */
    private Boolean available;

    /** 区间内最紧的一天还剩几间（available=false 时为 0 或负数） */
    private Integer minRemain;

    /** 逐天明细 */
    private List<DayStock> details;

    @Data
    @Builder
    public static class DayStock {
        private LocalDate stayDate;
        private Integer totalRooms;
        private Integer soldRooms;
        private Integer remain;
        /** 该天是否够（remain >= roomCount） */
        private Boolean enough;
    }
}
