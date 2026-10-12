package com.fancy.hotel.controller;

import com.fancy.hotel.common.Result;
import com.fancy.hotel.domain.dto.AvailabilityQueryDTO;
import com.fancy.hotel.domain.dto.CancelBookingDTO;
import com.fancy.hotel.domain.dto.CreateBookingDTO;
import com.fancy.hotel.domain.dto.PayBookingDTO;
import com.fancy.hotel.domain.dto.RescheduleBookingDTO;
import com.fancy.hotel.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 预订接口
 *
 * <p>⚠️ P0 阶段尚未接入鉴权：下单用请求体里的 userId，取消用请求体里的 cancelRole。
 * P4 接入 Agent / 鉴权后会改成从 token 或 ToolContext 取，不再信任请求体。
 */
@RestController
@RequestMapping("/booking")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /** 可订查询（只读，不占库存） */
    @PostMapping("/availability")
    public Result availability(@Valid @RequestBody AvailabilityQueryDTO dto) {
        return Result.ok(bookingService.checkAvailability(dto));
    }

    /** 报价（逐晚累加，可看到每晚单价） */
    @PostMapping("/quote")
    public Result quote(@Valid @RequestBody AvailabilityQueryDTO dto) {
        return Result.ok(bookingService.quotePrice(
                dto.getRoomTypeId(), dto.getCheckIn(), dto.getCheckOut(), dto.getRoomCount()));
    }

    /** 下单 */
    @PostMapping
    public Result create(@Valid @RequestBody CreateBookingDTO dto) {
        return Result.ok(bookingService.createBooking(dto));
    }

    /** 订单详情 */
    @GetMapping("/{bookingId}")
    public Result detail(@PathVariable Long bookingId) {
        return Result.ok(bookingService.getBooking(bookingId));
    }

    /** 某用户的订单列表 */
    @GetMapping("/user/{userId}")
    public Result listByUser(@PathVariable Long userId) {
        return Result.ok(bookingService.listByUser(userId));
    }

    /** 支付 */
    @PostMapping("/{bookingId}/pay")
    public Result pay(@PathVariable Long bookingId,
                      @RequestBody(required = false) PayBookingDTO dto) {
        bookingService.payBooking(bookingId, dto == null ? new PayBookingDTO() : dto);
        return Result.ok();
    }

    /** 取消（返回退款金额） */
    @PostMapping("/{bookingId}/cancel")
    public Result cancel(@PathVariable Long bookingId,
                         @RequestBody(required = false) CancelBookingDTO dto) {
        return Result.ok(bookingService.cancelBooking(bookingId, dto == null ? new CancelBookingDTO() : dto));
    }

    /**
     * 改期（只动库存差集，重叠日期不受影响）
     *
     * <p>返回 releasedDates / occupiedDates / untouchedDates 三个列表，
     * 可以直接看出"这次改期到底动了哪几天"。
     */
    @PostMapping("/{bookingId}/reschedule")
    public Result reschedule(@PathVariable Long bookingId,
                             @Valid @RequestBody RescheduleBookingDTO dto) {
        return Result.ok(bookingService.rescheduleBooking(bookingId, dto));
    }

    /**
     * 手动触发「超时未支付」扫描
     *
     * <p>P0 用来验证 10 -> 91 的失效链路；P2 会交给 Redis 延迟队列自动执行。
     */
    @PostMapping("/expire-scan")
    public Result expireScan() {
        return Result.ok(bookingService.expireTimeoutBookings());
    }
}
