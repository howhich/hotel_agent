package com.fancy.hotel.service;

import com.fancy.hotel.domain.dto.AvailabilityQueryDTO;
import com.fancy.hotel.domain.dto.CancelBookingDTO;
import com.fancy.hotel.domain.dto.CreateBookingDTO;
import com.fancy.hotel.domain.dto.PayBookingDTO;
import com.fancy.hotel.domain.dto.RescheduleBookingDTO;
import com.fancy.hotel.domain.vo.AvailabilityVO;
import com.fancy.hotel.domain.vo.BookingVO;
import com.fancy.hotel.domain.vo.CancelResultVO;
import com.fancy.hotel.domain.vo.PriceQuoteVO;
import com.fancy.hotel.domain.vo.RescheduleResultVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 预订服务
 */
public interface BookingService {

    /** 可订查询（只读，不占库存） */
    AvailabilityVO checkAvailability(AvailabilityQueryDTO dto);

    /** 报价（逐晚累加） */
    PriceQuoteVO quotePrice(Long roomTypeId, LocalDate checkIn, LocalDate checkOut, Integer roomCount);

    /** 下单：逐天占库存 → 写台账 → 建订单（同一事务） */
    Long createBooking(CreateBookingDTO dto);

    /** 订单详情 */
    BookingVO getBooking(Long bookingId);

    /** 某用户的订单列表 */
    List<BookingVO> listByUser(Long userId);

    /** 支付：10 -> 20 */
    void payBooking(Long bookingId, PayBookingDTO dto);

    /** 取消：10/20 -> 90，并按台账释放库存 */
    CancelResultVO cancelBooking(Long bookingId, CancelBookingDTO dto);

    /**
     * 改期：10/20 可改，只动台账的**差集**（重叠的日期完全不碰）
     *
     * <p>状态不变；返回差价（正数需补、负数应退）。间数不变。
     */
    RescheduleResultVO rescheduleBooking(Long bookingId, RescheduleBookingDTO dto);

    /** 单笔失效：10 -> 91（P2 会由延迟队列调用，P0 先提供手动入口） */
    boolean expireBooking(Long bookingId);

    /** 批量扫描超时未支付并失效，返回处理条数 */
    int expireTimeoutBookings();
}
