package com.fancy.hotel.job;

import com.fancy.hotel.service.BookingService;
import com.fancy.hotel.service.OrderDelayQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单超时处理：<b>两条路径</b>
 *
 * <pre>
 *   ① consumeDue()  每 1 秒          Redis 延迟队列精确触发（主路径）
 *   ② sweep()       每 5 分钟        扫表兜底（队列丢了任务时捞回来）
 * </pre>
 *
 * <p><b>为什么必须两条</b>：延迟队列是"待办任务"，属于唯一事实来源那类数据。
 * 它被淘汰 / Redis 重启 / 入队失败，任务就静默丢了 —— 订单永远不会被释放，
 * 库存被永久占着。所以扫表这条路不能省。
 *
 * <p><b>为什么两者都安全重复执行</b>：{@code expireBooking()} 内部用条件 UPDATE
 * （{@code WHERE booking_status = 10}）改状态，只有打中 1 行的那个才算成功。
 * 两条路径同时命中同一条订单，也只会释放一次库存。
 *
 * <p>★ 注意这里的调用都经过 {@code BookingService} 的代理，所以
 * {@code expireBooking} 的 {@code @Transactional} 是生效的。
 * 如果把循环写在 {@code BookingServiceImpl} 内部、用 {@code this.expireBooking()}
 * 调用，就变成自调用 —— 事务失效，{@code markCancelled} 和 {@code releaseInventory}
 * 会各自自动提交，中间失败就会留下"已取消但库存没释放"的脏数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutJob {

    private final BookingService bookingService;
    private final OrderDelayQueue orderDelayQueue;

    /**
     * 主路径：消费延迟队列里到点的任务
     */
    @Scheduled(fixedDelayString = "${hotel.booking.delay.scan-interval-ms:1000}")
    public void consumeDue() {
        List<Long> ids = orderDelayQueue.claimDue(null);
        if (ids.isEmpty()) {
            return;
        }
        log.debug("延迟队列认领到 {} 个到期任务", ids.size());
        int expired = 0;
        for (Long id : ids) {
            try {
                if (bookingService.expireBooking(id)) {
                    expired++;
                }
                // false = 订单已经是别的状态（已支付/已取消），正常现象
            } catch (Exception e) {
                // 单条失败不影响同批其它任务；它还会被扫表兜底捞到
                log.error("延迟队列消费失败: bookingId={}", id, e);
            }
        }
        if (expired > 0) {
            log.info("延迟队列释放了 {} 个超时订单", expired);
        }
    }

    /**
     * 兜底：扫表释放超时订单
     *
     * <p>跑得慢一点没关系，它的存在意义就是"队列丢了也能补上"。
     */
    @Scheduled(fixedDelayString = "${hotel.booking.delay.sweep-interval-ms:300000}")
    public void sweep() {
        int n = sweepOnce();
        if (n > 0) {
            log.warn("兜底扫表释放了 {} 个超时订单 —— 说明延迟队列可能漏了任务，"
                    + "可以调 GET /booking/delay/stat 看一下队列状态", n);
        }
    }

    /**
     * 手动触发一次扫表（也供 {@code POST /booking/expire-scan} 使用）
     *
     * @return 释放的订单数
     */
    public int sweepOnce() {
        List<Long> ids = bookingService.findTimeoutPendingIds(200);
        int n = 0;
        for (Long id : ids) {
            try {
                if (bookingService.expireBooking(id)) {
                    n++;
                }
            } catch (Exception e) {
                log.error("兜底扫表释放失败: bookingId={}", id, e);
            }
        }
        return n;
    }
}
