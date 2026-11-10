package com.fancy.hotel.service;

import com.fancy.hotel.mapper.RoomInventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 把「某天还没被任何人订过」的库存行建出来
 *
 * <h3>为什么需要单独一个类</h3>
 * 建行这件事<b>必须在事务之外</b>做（见下面的原因），而调用它的
 * {@code createBooking} 本身是 {@code @Transactional} 的。
 * 直接在同一个类里调用会被"自调用"绕过代理，{@code Propagation} 不生效 ——
 * 所以必须是<b>另一个 Bean</b>。
 *
 * <h3>为什么必须放在事务外</h3>
 * 原来的写法是在事务里：
 * <pre>
 *   INSERT IGNORE INTO room_inventory ...   -- 插入重复键时，InnoDB 会给已存在的行加【共享锁 S】
 *   UPDATE room_inventory SET sold_rooms = ...  -- 同一行要升级成【排他锁 X】
 * </pre>
 * 两个并发事务各持一个 S、各等对方先释放 → <b>死锁</b>。
 * 这是教科书级的 "S→X 锁升级死锁"，实测触发过，InnoDB 输出形如：
 * <pre>
 *   事务A: HOLDS S lock (uk_roomtype_date) / WAITING FOR X lock   ← 同一行
 *   事务B: HOLDS S lock (uk_roomtype_date) / WAITING FOR X lock   ← 同一行
 * </pre>
 *
 * <p>把建行挪出去（{@code NOT_SUPPORTED} + autocommit，语句执行完就提交并放锁）之后，
 * 事务内只剩下按 {@code stay_date} 升序的 {@code UPDATE}。
 * 所有事务的加锁顺序一致 → <b>不可能形成等待环</b> → 死锁从根上消失，
 * 而不是靠"出错重试"绕过去。
 *
 * <h3>副作用</h3>
 * 建行是先提交的，所以如果下单最后失败回滚，这些行会留下来 ——
 * 但它们只是 {@code sold_rooms = 0} 的"空行"，完全无害（本来就该存在）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryRowPreparer {

    private final RoomInventoryMapper roomInventoryMapper;

    /**
     * 幂等地补上缺失的库存行（已存在的行不会被改动）
     *
     * @param roomTypeId 房型
     * @param dates      入住期间的每一天
     * @param totalRooms 总房量快照
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void ensureRows(Long roomTypeId, List<LocalDate> dates, Integer totalRooms) {
        if (roomTypeId == null || dates == null || dates.isEmpty()) {
            return;
        }
        for (LocalDate d : dates) {
            int n = roomInventoryMapper.insertIgnore(roomTypeId, d, totalRooms);
            if (n > 0) {
                log.debug("补建库存行: roomTypeId={}, date={}, total={}", roomTypeId, d, totalRooms);
            }
        }
    }
}
