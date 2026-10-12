package com.fancy.hotel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.hotel.domain.entity.Booking;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 预订单
 *
 * <p>所有状态流转都走「条件 UPDATE + 看影响行数」——
 * 影响 0 行说明状态已被别人推进，调用方据此让位或报错。
 */
public interface BookingMapper extends BaseMapper<Booking> {

    /**
     * 支付：10 待支付 -> 20 已确认
     *
     * @return 1 = 成功；0 = 状态已不是待支付（重复支付/已取消）
     */
    @Update("UPDATE booking SET booking_status = 20, paid_amount = #{paidAmount}, "
            + "pay_time = NOW(), update_time = NOW() "
            + "WHERE booking_id = #{bookingId} AND booking_status = 10 AND is_deleted = 0")
    int markPaid(@Param("bookingId") Long bookingId,
                 @Param("paidAmount") BigDecimal paidAmount);

    /**
     * 取消 / 失效：把期望状态改成目标状态
     *
     * @param fromStatus 期望的当前状态（10 或 20）
     * @param toStatus   目标状态（90 已取消 / 91 已失效）
     * @return 1 = 成功；0 = 状态已变化
     */
    @Update("UPDATE booking SET booking_status = #{toStatus}, cancel_role = #{cancelRole}, "
            + "cancel_reason = #{reason}, refund_amount = #{refundAmount}, "
            + "cancel_time = NOW(), update_time = NOW() "
            + "WHERE booking_id = #{bookingId} AND booking_status = #{fromStatus} AND is_deleted = 0")
    int markCancelled(@Param("bookingId") Long bookingId,
                      @Param("fromStatus") Integer fromStatus,
                      @Param("toStatus") Integer toStatus,
                      @Param("cancelRole") Integer cancelRole,
                      @Param("reason") String reason,
                      @Param("refundAmount") BigDecimal refundAmount);

    /**
     * 改期：更新入住区间与总价
     *
     * <p>带上 {@code expectStatus} 做 CAS —— 防止"读到订单后、改期落库前"
     * 订单被别人取消/支付掉。影响 0 行说明状态已变。
     *
     * @return 1 = 成功；0 = 状态已不是 expectStatus
     */
    @Update("UPDATE booking SET check_in = #{newCheckIn}, check_out = #{newCheckOut}, "
            + "nights = #{nights}, total_amount = #{newTotalAmount}, update_time = NOW() "
            + "WHERE booking_id = #{bookingId} AND booking_status = #{expectStatus} AND is_deleted = 0")
    int markRescheduled(@Param("bookingId") Long bookingId,
                        @Param("newCheckIn") LocalDate newCheckIn,
                        @Param("newCheckOut") LocalDate newCheckOut,
                        @Param("nights") Integer nights,
                        @Param("newTotalAmount") BigDecimal newTotalAmount,
                        @Param("expectStatus") Integer expectStatus);
}
