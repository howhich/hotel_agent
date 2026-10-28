package com.fancy.hotel.controller;

import com.fancy.hotel.common.Result;
import com.fancy.hotel.job.OrderTimeoutJob;
import com.fancy.hotel.service.OrderDelayQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单超时延迟队列的运维接口
 *
 * <p>延迟队列是"待办任务"，它有两条被观测的需求：
 * <ol>
 *   <li><b>它还健康吗</b> —— {@code stat} 看深度、到期未处理数、最老任务等了多久</li>
 *   <li><b>它丢了吗</b> —— {@code rebuild} 从 DB 里所有待支付订单重新算触发时刻入队</li>
 * </ol>
 *
 * <p>什么时候会用到 rebuild：Redis 重启、被淘汰、或者手工清过 key。
 * 因为它不设 TTL（故意的），`allkeys-lru` 下本来会被淘汰 —— 服务器已改成
 * `volatile-lru`，所以正常情况不该丢。但运维工具还是要备着。
 */
@RestController
@RequestMapping("/booking/delay")
@RequiredArgsConstructor
public class DelayQueueController {

    private final OrderDelayQueue orderDelayQueue;
    private final OrderTimeoutJob orderTimeoutJob;

    /** 队列统计：depth / due / oldestAgeSeconds */
    @GetMapping("/stat")
    public Result stat() {
        return Result.ok(orderDelayQueue.stat());
    }

    /** 从 DB 重建队列（队列丢了或想强制对齐时用） */
    @PostMapping("/rebuild")
    public Result rebuild() {
        int n = orderDelayQueue.rebuildFromDb();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requeued", n);
        out.put("stat", orderDelayQueue.stat());
        return Result.ok(out);
    }

    /** 手动跑一次兜底扫表（等价于 POST /booking/expire-scan） */
    @PostMapping("/sweep")
    public Result sweep() {
        int n = orderTimeoutJob.sweepOnce();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expired", n);
        return Result.ok(out);
    }
}
