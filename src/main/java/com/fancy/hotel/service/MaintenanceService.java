package com.fancy.hotel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.hotel.domain.entity.IdempotentRecord;
import com.fancy.hotel.mapper.IdempotentRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 维护 / 清理
 *
 * <p>P2.4：把"会无限增长"和"会悄悄残留"的两类数据定期收掉。
 *
 * <table border="1">
 *   <caption>清什么、为什么</caption>
 *   <tr><th>对象</th><th>为什么会涨/残留</th><th>清掉的依据</th></tr>
 *   <tr>
 *     <td>{@code idempotent_record}</td>
 *     <td>每次下单插一行，从来没人删</td>
 *     <td>{@code expire_time < now}（= 创建时间 + TTL，过了幂等窗口就没用了）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code hotel:delay:order} 的成员</td>
 *     <td>正常在支付/取消时 ZREM，但 Redis 抖动时可能没删掉；
 *         订单被删了也会留下孤儿成员</td>
 *     <td>member 对应的订单<b>不处于待支付</b>，或根本不存在</td>
 *   </tr>
 * </table>
 *
 * <p><b>这两件事都只删"确定没用"的数据</b>：
 * 幂等记录过了 TTL 就过了契约窗口；延迟队列里已终态的任务永远不会再被处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private final IdempotentRecordMapper idempotentRecordMapper;
    private final OrderDelayQueue orderDelayQueue;

    @Value("${hotel.maintenance.idempotent-batch:5000}")
    private int idempotentBatch;

    @Value("${hotel.maintenance.delay-purge-batch:1000}")
    private int delayPurgeBatch;

    /**
     * 清理过期的幂等记录
     *
     * @return 删除条数
     */
    public int cleanExpiredIdempotent() {
        int total = 0;
        // 分批删，避免一次锁太多行
        for (int round = 0; round < 10; round++) {
            int n = idempotentRecordMapper.delete(new LambdaQueryWrapper<IdempotentRecord>()
                    .lt(IdempotentRecord::getExpireTime, LocalDateTime.now())
                    .last("limit " + Math.max(1, idempotentBatch)));
            total += n;
            if (n < idempotentBatch) {
                break;
            }
        }
        if (total > 0) {
            log.info("清理过期幂等记录：删了 {} 条", total);
        }
        return total;
    }

    /**
     * 清理延迟队列里"已经不该存在"的成员
     *
     * @return 移除条数
     */
    public int purgeStaleDelayMembers() {
        return orderDelayQueue.purgeStaleMembers(delayPurgeBatch);
    }

    /** 跑一次全部清理 */
    public Map<String, Object> cleanup() {
        int idem = cleanExpiredIdempotent();
        int delay = purgeStaleDelayMembers();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idempotentDeleted", idem);
        out.put("delayMembersPurged", delay);
        return out;
    }

    /** 看一眼现在有多少可清的 */
    public Map<String, Object> stat() {
        long records = nz(idempotentRecordMapper.selectCount(null));
        long expired = nz(idempotentRecordMapper.selectCount(
                new LambdaQueryWrapper<IdempotentRecord>()
                        .lt(IdempotentRecord::getExpireTime, LocalDateTime.now())));
        OrderDelayQueue.DelayStat ds = orderDelayQueue.stat();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idempotentRecords", records);
        out.put("expiredIdempotentRecords", expired);
        out.put("delayDepth", ds.depth());
        out.put("delayDue", ds.due());
        out.put("delayOldestAgeSeconds", ds.oldestAgeSeconds());
        return out;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
