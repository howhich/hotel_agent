package com.fancy.hotel.job;

import com.fancy.hotel.service.MaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期清理（P2.4）
 *
 * <p>两个任务都只删「确定没用」的数据，所以失败也无所谓 —— catch 住记日志，下次再来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CleanupJob {

    private final MaintenanceService maintenanceService;

    /**
     * 清过期幂等记录
     *
     * <p>每天凌晨跑一次。幂等窗口只有 {@code ttlSeconds}（默认 600 秒），
     * 所以过期的记录不可能再被任何重试命中。
     */
    @Scheduled(cron = "${hotel.maintenance.idempotent-clean-cron:0 30 3 * * ?}")
    public void cleanIdempotent() {
        try {
            maintenanceService.cleanExpiredIdempotent();
        } catch (Exception e) {
            log.error("清理过期幂等记录失败", e);
        }
    }

    /**
     * 清延迟队列里的残留成员
     *
     * <p>每 30 分钟一次。正常路径会把它们删掉，这里只是兜底。
     */
    @Scheduled(cron = "${hotel.maintenance.delay-purge-cron:0 */30 * * * ?}")
    public void purgeDelayMembers() {
        try {
            maintenanceService.purgeStaleDelayMembers();
        } catch (Exception e) {
            log.error("清理延迟队列残留失败", e);
        }
    }
}
