package com.fancy.hotel.controller;

import com.fancy.hotel.common.Result;
import com.fancy.hotel.service.MaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维护 / 清理的运维接口
 *
 * <pre>
 *   GET  /booking/maintenance/stat      看一眼有多少可清的
 *   POST /booking/maintenance/cleanup   立刻清一次（定时任务跑的还是同一套逻辑）
 * </pre>
 */
@RestController
@RequestMapping("/booking/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    @GetMapping("/stat")
    public Result stat() {
        return Result.ok(maintenanceService.stat());
    }

    @PostMapping("/cleanup")
    public Result cleanup() {
        return Result.ok(maintenanceService.cleanup());
    }
}
