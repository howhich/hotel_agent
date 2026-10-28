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

    /** 单笔失效：10 -> 91（延迟队列和兜底扫表都调它） */
    boolean expireBooking(Long bookingId);

    /**
     * 查出「已超时但仍待支付」的订单 ID（只查询，不处理）
     *
     * <p>★ 批量失效的循环放在 {@code OrderTimeoutJob} 里，不在这里。
     * 因为如果在同一个 Bean 内部用 {@code this.expireBooking()} 循环，
     * 就是自调用 —— Spring 代理不参与，{@code @Transactional} 直接失效，
     * {@code markCancelled} 和 {@code releaseInventory} 会各自自动提交，
     * 中间失败就留下"已取消但库存没释放"的脏数据。
     */
    List<Long> findTimeoutPendingIds(int limit);
}
