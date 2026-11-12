package com.fancy.hotel.service.agent;

import com.fancy.hotel.domain.vo.BookingVO;
import com.fancy.hotel.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 的"我的订单"工具（P2）
 *
 * <p>只做一件事：把用户<b>还能操作</b>的订单捞出来。
 *
 * <h3>为什么只留 10 / 20</h3>
 * <pre>
 *   10 PENDING_PAYMENT  待支付  -> 能取消、能改期
 *   20 CONFIRMED        已确认  -> 能取消、能改期
 *   30 CHECKED_IN / 40 COMPLETED / 90 CANCELLED / 91 EXPIRED  -> 都是终态或已入住
 * </pre>
 * 把已取消、已失效的混进来只会让用户困惑："我要取消那个，它列了 5 个，其中 3 个早就没了"。
 *
 * <h3>为什么不自己写 SQL</h3>
 * 直接复用 {@code bookingService.listByUser(userId)} —— 它已经做了归属校验、
 * 按创建时间倒序、以及 VO 转换。Agent 不该另算一套，否则两边的口径迟早会不一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MyBookingTool {

    private static final int PENDING_PAYMENT = 10;
    private static final int CONFIRMED = 20;

    private final BookingService bookingService;

    /** 当前用户还能操作的订单（待支付 / 已确认），按创建时间倒序 */
    public List<BookingVO> activeBookings(Long userId) {
        List<BookingVO> all = bookingService.listByUser(userId);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        return all.stream()
                .filter(v -> v.getBookingStatus() != null
                        && (v.getBookingStatus() == PENDING_PAYMENT
                            || v.getBookingStatus() == CONFIRMED))
                .toList();
    }
}
