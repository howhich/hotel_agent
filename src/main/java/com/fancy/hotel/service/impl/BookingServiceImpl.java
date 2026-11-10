package com.fancy.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.hotel.common.BusinessException;
import com.fancy.hotel.common.Idempotent;
import com.fancy.hotel.domain.dto.AvailabilityQueryDTO;
import com.fancy.hotel.domain.dto.CancelBookingDTO;
import com.fancy.hotel.domain.dto.CreateBookingDTO;
import com.fancy.hotel.domain.dto.PayBookingDTO;
import com.fancy.hotel.domain.dto.RescheduleBookingDTO;
import com.fancy.hotel.domain.entity.Booking;
import com.fancy.hotel.domain.entity.BookingInventoryLink;
import com.fancy.hotel.domain.entity.Hotel;
import com.fancy.hotel.domain.entity.RoomInventory;
import com.fancy.hotel.domain.entity.RoomPrice;
import com.fancy.hotel.domain.entity.RoomType;
import com.fancy.hotel.domain.enums.BookingStatus;
import com.fancy.hotel.domain.enums.CancelPolicy;
import com.fancy.hotel.domain.enums.CancelRole;
import com.fancy.hotel.domain.vo.AvailabilityVO;
import com.fancy.hotel.domain.vo.BookingVO;
import com.fancy.hotel.domain.vo.CancelResultVO;
import com.fancy.hotel.domain.vo.PriceQuoteVO;
import com.fancy.hotel.domain.vo.RescheduleResultVO;
import com.fancy.hotel.mapper.BookingInventoryLinkMapper;
import com.fancy.hotel.mapper.BookingMapper;
import com.fancy.hotel.mapper.HotelMapper;
import com.fancy.hotel.mapper.RoomInventoryMapper;
import com.fancy.hotel.mapper.RoomPriceMapper;
import com.fancy.hotel.mapper.RoomTypeMapper;
import com.fancy.hotel.service.BookingService;
import com.fancy.hotel.service.AbsentCache;
import com.fancy.hotel.service.InventoryCacheService;
import com.fancy.hotel.service.InventoryRowPreparer;
import com.fancy.hotel.service.OrderDelayQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 预订服务实现
 *
 * <p><b>本类的核心思想</b>（P0 阶段，尚无 Redis）：
 * <ul>
 *   <li>库存以「天」为行，扣减是<b>逐天的条件 UPDATE</b>：
 *       {@code WHERE sold_rooms + n <= total_rooms}；
 *       任何一天影响 0 行 → 抛异常 → <b>整个事务回滚</b>，前面几天已扣的自动还回去。</li>
 *   <li>释放库存一律<b>按台账 {@code booking_inventory_link} 逐天还原</b>，不重新推导区间。</li>
 *   <li>所有状态流转都是「条件 UPDATE + 看影响行数」，影响 0 行说明状态已被别人推进。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private static final int MAX_NIGHTS = 30;
    private static final int MAX_ROOMS = 5;

    // ---------- 依赖 ----------
    private final HotelMapper hotelMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final RoomInventoryMapper roomInventoryMapper;
    private final RoomPriceMapper roomPriceMapper;
    private final BookingMapper bookingMapper;
    private final BookingInventoryLinkMapper linkMapper;
    private final ObjectMapper objectMapper;
    private final InventoryCacheService inventoryCacheService;
    private final AbsentCache absentCache;
    private final InventoryRowPreparer inventoryRowPreparer;
    private final OrderDelayQueue orderDelayQueue;

    @Value("${hotel.booking.pay-timeout-minutes:15}")
    private int payTimeoutMinutes;

    // =========================================================================
    // 1. 可订查询（只读）
    // =========================================================================

    @Override
    public AvailabilityVO checkAvailability(AvailabilityQueryDTO dto) {
        validateRange(dto.getCheckIn(), dto.getCheckOut());
        int roomCount = normalizeRoomCount(dto.getRoomCount());
        RoomType rt = requireRoomType(dto.getRoomTypeId());
        int defaultTotal = rt.getTotalRooms() == null ? 0 : rt.getTotalRooms();

        List<LocalDate> dates = stayDates(dto.getCheckIn(), dto.getCheckOut());
        List<RoomInventory> stocks = roomInventoryMapper.selectRange(
                dto.getRoomTypeId(), dto.getCheckIn(), dto.getCheckOut());

        // 按日期索引，方便补齐"没有库存行"的天
        java.util.Map<LocalDate, RoomInventory> stockMap = new java.util.HashMap<>();
        for (RoomInventory s : stocks) {
            stockMap.put(s.getStayDate(), s);
        }

        List<AvailabilityVO.DayStock> details = new ArrayList<>(dates.size());
        int minRemain = Integer.MAX_VALUE;
        boolean available = true;

        for (LocalDate d : dates) {
            RoomInventory s = stockMap.get(d);
            // 没有库存行 = 该天还没有人订过（库存行是下单时惰性创建的），
            // 所以按房型总房量算「满房可订」，而不是当成 0 间。
            int total = s == null ? defaultTotal : s.getTotalRooms();
            int sold = s == null ? 0 : s.getSoldRooms();
            int remain = total - sold;
            boolean enough = remain >= roomCount;
            if (remain < minRemain) {
                minRemain = remain;
            }
            if (!enough) {
                available = false;
            }
            details.add(AvailabilityVO.DayStock.builder()
                    .stayDate(d).totalRooms(total).soldRooms(sold)
                    .remain(remain).enough(enough).build());
        }

        return AvailabilityVO.builder()
                .roomTypeId(dto.getRoomTypeId())
                .checkIn(dto.getCheckIn())
                .checkOut(dto.getCheckOut())
                .nights(dates.size())
                .roomCount(roomCount)
                .available(available)
                .minRemain(minRemain == Integer.MAX_VALUE ? 0 : minRemain)
                .details(details)
                .build();
    }

    // =========================================================================
    // 2. 报价：逐晚累加
    // =========================================================================

    @Override
    public PriceQuoteVO quotePrice(Long roomTypeId, LocalDate checkIn, LocalDate checkOut, Integer roomCount) {
        validateRange(checkIn, checkOut);
        int rooms = normalizeRoomCount(roomCount);

        List<LocalDate> dates = stayDates(checkIn, checkOut);
        List<RoomPrice> prices = roomPriceMapper.selectRange(roomTypeId, checkIn, checkOut);

        java.util.Map<LocalDate, RoomPrice> priceMap = new java.util.HashMap<>();
        for (RoomPrice p : prices) {
            priceMap.put(p.getStayDate(), p);
        }

        List<PriceQuoteVO.NightPrice> nightPrices = new ArrayList<>(dates.size());
        BigDecimal perRoom = BigDecimal.ZERO;

        for (LocalDate d : dates) {
            RoomPrice p = priceMap.get(d);
            if (p == null) {
                throw BusinessException.conflict(d + " 暂未开放预订（缺少价格日历）");
            }
            BigDecimal price = p.effectivePrice();
            perRoom = perRoom.add(price);
            nightPrices.add(PriceQuoteVO.NightPrice.builder()
                    .stayDate(d)
                    .price(price)
                    .weekend(isWeekend(d))
                    .build());
        }

        BigDecimal total = perRoom.multiply(BigDecimal.valueOf(rooms)).setScale(2, RoundingMode.HALF_UP);

        return PriceQuoteVO.builder()
                .roomTypeId(roomTypeId)
                .nights(dates.size())
                .roomCount(rooms)
                .nightPrices(nightPrices)
                .pricePerRoom(perRoom.setScale(2, RoundingMode.HALF_UP))
                .totalAmount(total)
                .build();
    }

    // =========================================================================
    // 3. ★ 下单
    // =========================================================================

    @Override
    @Idempotent(bizType = "CREATE_BOOKING", ttlSeconds = 600)
    @Transactional(rollbackFor = Exception.class)
    public Long createBooking(CreateBookingDTO dto) {
        validateRange(dto.getCheckIn(), dto.getCheckOut());
        int roomCount = normalizeRoomCount(dto.getRoomCount());

        RoomType rt = requireRoomType(dto.getRoomTypeId());
        if (rt.getHotelId() != null && dto.getHotelId() != null && !rt.getHotelId().equals(dto.getHotelId())) {
            throw BusinessException.badRequest("房型与酒店不匹配");
        }
        Hotel hotel = findHotel(dto.getHotelId());
        if (hotel == null) {
            throw BusinessException.notFound("酒店不存在: " + dto.getHotelId());
        }

        // 先报价：缺价格日历会在这里就被拦下，不会白占库存
        PriceQuoteVO quote = quotePrice(dto.getRoomTypeId(), dto.getCheckIn(), dto.getCheckOut(), roomCount);

        long bookingId = IdWorker.getId();
        List<LocalDate> dates = stayDates(dto.getCheckIn(), dto.getCheckOut());

        // ---------- ★ Redis 预检预占（快路径，但不是裁决者）----------
        //   OK         -> 缓存已预占，继续走 DB；万一事务没提交，回调里把预占还回去
        //   REJECTED   -> 缓存说不够，直接拒，省掉一次 DB 往返
        //   CACHE_MISS -> 缓存不可用，静默退化为纯 DB 路径（绝不因此让用户下单失败）
        InventoryCacheService.ReserveResult reserve =
                inventoryCacheService.tryReserve(dto.getRoomTypeId(), rt.getTotalRooms(), dates, roomCount);
        if (reserve.status() == InventoryCacheService.ReserveStatus.REJECTED) {
            log.info("下单被 Redis 预检拦下: roomTypeId={}, date={}, need={}",
                    dto.getRoomTypeId(), reserve.rejectedDate(), roomCount);
            throw BusinessException.conflict(reserve.rejectedDate() + " 房量不足，请调整日期或间数");
        }
        if (reserve.status() == InventoryCacheService.ReserveStatus.OK) {
            // ★ 补偿放在事务回调里，而不是方法体的 catch 里：
            //   只有这样，连"提交阶段才失败"（比如提交时死锁）也能补偿到。
            afterTransaction(null, () -> {
                int back = inventoryCacheService.releaseAll(dto.getRoomTypeId(), dates, roomCount);
                log.warn("下单事务未提交，已回滚 Redis 预占: roomTypeId={}, 回滚 {} 天",
                        dto.getRoomTypeId(), back);
            });
        }

        // ---------- ★ 逐天占库存（DB 才是裁决者）----------
        //
        // ★ 先把缺的库存行建好，而且必须在【事务之外】建。
        //
        //   原来是在事务里 INSERT IGNORE 再 UPDATE，并发下单同一天时会死锁：
        //     InnoDB 插入重复键会给已存在的行加【共享锁 S】，
        //     紧接着的 UPDATE 又要把同一行升级成【排他锁 X】——
        //     两个事务各持一个 S、各等对方先放，InnoDB 只能回滚其中一个。
        //   （实测触发过，报错是 DeadlockLoserDataAccessException，500 给到用户）
        //
        //   改成事务外建行之后，事务内只剩"按日期升序的 UPDATE"，
        //   所有事务加锁顺序一致，不可能成环 —— 死锁从根上消失。
        inventoryRowPreparer.ensureRows(dto.getRoomTypeId(), dates, rt.getTotalRooms());

        for (LocalDate d : dates) {
            // 条件扣减：够才扣
            int updated = roomInventoryMapper.tryOccupy(dto.getRoomTypeId(), d, roomCount);
            if (updated == 0) {
                // 抛异常 → 整个事务回滚 → 前面几天已扣的自动还回去
                log.info("下单失败，{} 房量不足: roomTypeId={}, need={}", d, dto.getRoomTypeId(), roomCount);
                throw BusinessException.conflict(d + " 房量不足，请调整日期或间数");
            }
        }

        // ---------- 写占用台账 ----------
        for (LocalDate d : dates) {
            linkMapper.insert(BookingInventoryLink.builder()
                    .bookingId(bookingId)
                    .roomTypeId(dto.getRoomTypeId())
                    .stayDate(d)
                    .roomCount(roomCount)
                    .released(0)
                    .build());
        }

        // ---------- 建订单 ----------
        CancelPolicy policy = CancelPolicy.fromCode(rt.getCancelPolicy());
        Booking booking = Booking.builder()
                .bookingId(bookingId)
                .userId(dto.getUserId())
                .hotelId(dto.getHotelId())
                .roomTypeId(dto.getRoomTypeId())
                .checkIn(dto.getCheckIn())
                .checkOut(dto.getCheckOut())
                .nights(dates.size())
                .roomCount(roomCount)
                .guestCount(dto.getGuestCount() == null ? 1 : dto.getGuestCount())
                .withBreakfast(dto.getWithBreakfast() == null ? 0 : dto.getWithBreakfast())
                .totalAmount(quote.getTotalAmount())
                .bookingStatus(BookingStatus.PENDING_PAYMENT.getCode())
                .cancelPolicySnapshot(policy.snapshotJson())
                .isDeleted(0)
                .build();
        bookingMapper.insert(booking);

        // ★ 入队放在【事务提交之后】：回滚的订单不该留在延迟队列里，
        //   否则到点会触发一次"订单不存在"的空转。
        long executeAt = orderDelayQueue.executeAtFromNow();
        afterTransaction(() -> orderDelayQueue.enqueue(bookingId, executeAt), null);

        log.info("下单成功: bookingId={}, roomTypeId={}, {}~{} ({} 晚 × {} 间), 总价={}",
                bookingId, dto.getRoomTypeId(), dto.getCheckIn(), dto.getCheckOut(),
                dates.size(), roomCount, quote.getTotalAmount());
        return bookingId;
    }

    // =========================================================================
    // 4. 查询
    // =========================================================================

    @Override
    public BookingVO getBooking(Long bookingId) {
        Booking b = requireBooking(bookingId);
        return toVO(b);
    }

    @Override
    public List<BookingVO> listByUser(Long userId) {
        List<Booking> list = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getUserId, userId)
                .orderByDesc(Booking::getCreateTime));
        List<BookingVO> result = new ArrayList<>(list.size());
        for (Booking b : list) {
            result.add(toVO(b));
        }
        return result;
    }

    // =========================================================================
    // 5. 支付
    // =========================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void payBooking(Long bookingId, PayBookingDTO dto) {
        Booking b = requireBooking(bookingId);
        BookingStatus st = BookingStatus.fromCode(b.getBookingStatus());
        if (!st.payable()) {
            throw BusinessException.conflict("当前状态（" + st.getDesc() + "）不可支付");
        }
        int updated = bookingMapper.markPaid(bookingId, b.getTotalAmount());
        if (updated == 0) {
            // 条件 UPDATE 没打中：状态在读取之后被别人改了
            throw BusinessException.conflict("订单状态已变化，请刷新后重试");
        }
        // 已经付过了，不必再等到点触发
        dequeueOnCommit(bookingId);
        log.info("支付成功: bookingId={}, amount={}, tradeNo={}", bookingId, b.getTotalAmount(),
                dto == null ? null : dto.getTradeNo());
    }

    // =========================================================================
    // 6. 取消
    // =========================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CancelResultVO cancelBooking(Long bookingId, CancelBookingDTO dto) {
        Booking b = requireBooking(bookingId);
        BookingStatus st = BookingStatus.fromCode(b.getBookingStatus());
        if (!st.cancellable()) {
            throw BusinessException.conflict("当前状态（" + st.getDesc() + "）不可取消");
        }

        // ---- 退款计算：读【订单快照】，不是读当前枚举 ----
        SnapshotPolicy snap = parseSnapshot(b.getCancelPolicySnapshot());
        long hoursBefore = hoursUntilCheckIn(b.getCheckIn());
        BigDecimal ratio = snap.refundRatio(hoursBefore);

        BigDecimal paid = b.getPaidAmount() == null ? BigDecimal.ZERO : b.getPaidAmount();
        BigDecimal refund = paid.multiply(ratio).setScale(2, RoundingMode.HALF_UP);

        int cancelRole = dto == null || dto.getCancelRole() == null
                ? CancelRole.USER.getCode() : dto.getCancelRole();
        String reason = dto == null ? null : dto.getReason();

        // ---- 条件 UPDATE 改状态 ----
        int updated = bookingMapper.markCancelled(bookingId, st.getCode(),
                BookingStatus.CANCELLED.getCode(), cancelRole, reason, refund);
        if (updated == 0) {
            throw BusinessException.conflict("订单状态已变化，请刷新后重试");
        }

        // ---- 按台账释放库存 ----
        releaseInventory(bookingId);

        dequeueOnCommit(bookingId);   // 已取消，不必再等到点触发

        log.info("取消成功: bookingId={}, {} -> 90, 可退比例={}, 退款={}",
                bookingId, st.getDesc(), ratio.toPlainString(), refund);

        return CancelResultVO.builder()
                .bookingId(bookingId)
                .bookingStatus(BookingStatus.CANCELLED.getCode())
                .statusDesc(BookingStatus.CANCELLED.getDesc())
                .paidAmount(paid)
                .refundAmount(refund)
                .refundRatioText(ratio.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%")
                .policyName(snap.policyName())
                .reason(reason)
                .build();
    }

    // =========================================================================
    // 7. ★ 改期（台账价值的直接体现）
    //
    //   核心：只动【差集】，重叠的日期一根手指都不碰。
    //
    //   为什么不能"先全释放、再全占用"：
    //     如果把 10/12~10/15 改成 10/13~10/16，先全释放再全占用的话，
    //     10/13、10/14 会在中间那一刻被放开 —— 万一被别人抢走，
    //     用户自己"续住"反而失败，而这两天本来一直是他占着的。
    //   只有台账能告诉我们"他到底占着哪几天"，从而算出真正的差集。
    // =========================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RescheduleResultVO rescheduleBooking(Long bookingId, RescheduleBookingDTO dto) {
        Booking b = requireBooking(bookingId);
        BookingStatus st = BookingStatus.fromCode(b.getBookingStatus());

        // 可取消 = 未入住未终态 = 待支付 / 已确认。这两个状态才允许改期。
        if (!st.cancellable()) {
            throw BusinessException.conflict("当前状态（" + st.getDesc() + "）不可改期");
        }
        validateRange(dto.getNewCheckIn(), dto.getNewCheckOut());
        if (dto.getNewCheckIn().equals(b.getCheckIn()) && dto.getNewCheckOut().equals(b.getCheckOut())) {
            throw BusinessException.badRequest("新日期与当前入住区间相同，无需改期");
        }

        int roomCount = b.getRoomCount() == null ? 1 : b.getRoomCount();
        RoomType rt = requireRoomType(b.getRoomTypeId());

        List<LocalDate> oldDays = stayDates(b.getCheckIn(), b.getCheckOut());
        List<LocalDate> newDays = stayDates(dto.getNewCheckIn(), dto.getNewCheckOut());

        // ---------- ★ 三方切分：这是整个改期的关键 ----------
        Set<LocalDate> oldSet = new LinkedHashSet<>(oldDays);
        Set<LocalDate> newSet = new LinkedHashSet<>(newDays);

        List<LocalDate> toRelease = oldDays.stream().filter(d -> !newSet.contains(d)).toList();
        List<LocalDate> toOccupy = newDays.stream().filter(d -> !oldSet.contains(d)).toList();
        List<LocalDate> untouched = newDays.stream().filter(oldSet::contains).toList();

        // 先算新价：缺价格日历会在这里就抛错，不会白释放一批库存
        BigDecimal newTotal = quotePrice(b.getRoomTypeId(),
                dto.getNewCheckIn(), dto.getNewCheckOut(), roomCount).getTotalAmount();

        // ---------- ★ Redis 预检预占（只针对新增加的天）----------
        if (!toOccupy.isEmpty()) {
            InventoryCacheService.ReserveResult reserve = inventoryCacheService.tryReserve(
                    b.getRoomTypeId(), rt.getTotalRooms(), toOccupy, roomCount);
            if (reserve.status() == InventoryCacheService.ReserveStatus.REJECTED) {
                throw BusinessException.conflict(reserve.rejectedDate() + " 房量不足，改期失败");
            }
            if (reserve.status() == InventoryCacheService.ReserveStatus.OK) {
                // 事务没提交，就把新增加那几天的 Redis 预占还回去
                afterTransaction(null, () ->
                        inventoryCacheService.releaseAll(b.getRoomTypeId(), toOccupy, roomCount));
            }
        }

        // ---------- ① 释放"旧有新无"的天 ----------
        for (LocalDate d : toRelease) {
            releaseOneDay(bookingId, b.getRoomTypeId(), d, roomCount);
        }

        // ---------- ② 占用"新有旧无"的天 ----------
        for (LocalDate d : toOccupy) {
            roomInventoryMapper.insertIgnore(b.getRoomTypeId(), d, rt.getTotalRooms());
            int occupied = roomInventoryMapper.tryOccupy(b.getRoomTypeId(), d, roomCount);
            if (occupied == 0) {
                // 抛异常 → 整个事务回滚 → ① 里已释放的天自动还回来
                log.info("改期失败，{} 房量不足: bookingId={}, need={}", d, bookingId, roomCount);
                throw BusinessException.conflict(d + " 房量不足，改期失败");
            }
            // 认领占用；若撞上该单历史已释放的行，upsert 会把它翻回 released=0
            linkMapper.upsertHold(bookingId, b.getRoomTypeId(), d, roomCount);
        }

        // ---------- ★ Redis 侧：等事务提交后再释放"旧有新无"的那几天 ----------
        // 注意：releaseOneDay() 只动 DB；Redis 的释放统一在这里做，避免重复释放。
        if (!toRelease.isEmpty()) {
            afterTransaction(
                    () -> inventoryCacheService.releaseAll(b.getRoomTypeId(), toRelease, roomCount),
                    null);
        }

        // ---------- ③ 更新订单（CAS：状态没被别人改才落库） ----------
        int updated = bookingMapper.markRescheduled(bookingId,
                dto.getNewCheckIn(), dto.getNewCheckOut(), newDays.size(), newTotal, st.getCode());
        if (updated == 0) {
            throw BusinessException.conflict("订单状态已变化，改期失败");
        }

        BigDecimal oldTotal = b.getTotalAmount() == null ? BigDecimal.ZERO : b.getTotalAmount();
        BigDecimal diff = newTotal.subtract(oldTotal).setScale(2, RoundingMode.HALF_UP);

        log.info("改期成功: bookingId={}, {}~{} -> {}~{}, 释放 {} 天 / 新占 {} 天 / 没动 {} 天, 差价={}",
                bookingId, b.getCheckIn(), b.getCheckOut(), dto.getNewCheckIn(), dto.getNewCheckOut(),
                toRelease.size(), toOccupy.size(), untouched.size(), diff);

        return RescheduleResultVO.builder()
                .bookingId(bookingId)
                .oldCheckIn(b.getCheckIn())
                .oldCheckOut(b.getCheckOut())
                .oldNights(b.getNights())
                .newCheckIn(dto.getNewCheckIn())
                .newCheckOut(dto.getNewCheckOut())
                .newNights(newDays.size())
                .oldTotalAmount(oldTotal)
                .newTotalAmount(newTotal)
                .priceDiff(diff)
                .releasedDates(toRelease)
                .occupiedDates(toOccupy)
                .untouchedDates(untouched)
                .bookingStatus(st.getCode())
                .statusDesc(st.getDesc())
                .build();
    }

    /** 改期时释放单独一天：先抢占式标记，再还库存 */
    private void releaseOneDay(Long bookingId, Long roomTypeId, LocalDate stayDate, int roomCount) {
        int marked = linkMapper.markReleasedByDate(bookingId, stayDate);
        if (marked == 0) {
            log.warn("改期释放：台账行不存在或已释放 bookingId={}, date={}", bookingId, stayDate);
            return;
        }
        int released = roomInventoryMapper.release(roomTypeId, stayDate, roomCount);
        if (released == 0) {
            log.error("改期释放库存异常（台账与库存不一致）: bookingId={}, date={}, n={}",
                    bookingId, stayDate, roomCount);
        }
    }

    // =========================================================================
    // 8. 失效（系统超时）
    // =========================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean expireBooking(Long bookingId) {
        Booking b = bookingMapper.selectOne(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getBookingId, bookingId));
        if (b == null) {
            return false;
        }
        // 只处理仍停留在"待支付"的：已支付/已取消的让位，不抢用户的动作
        Integer current = b.getBookingStatus();
        if (current == null || current != BookingStatus.PENDING_PAYMENT.getCode()) {
            return false;
        }
        int updated = bookingMapper.markCancelled(bookingId,
                BookingStatus.PENDING_PAYMENT.getCode(),
                BookingStatus.EXPIRED.getCode(),
                CancelRole.SYSTEM.getCode(),
                "超时未支付，系统自动释放",
                BigDecimal.ZERO);
        if (updated == 0) {
            return false;
        }
        releaseInventory(bookingId);
        // 队列里可能还有它的残留（比如这条是兜底扫表触发的，成员并没被认领过），顺手清掉
        dequeueOnCommit(bookingId);
        log.info("订单超时失效: bookingId={}", bookingId);
        return true;
    }

    @Override
    public List<Long> findTimeoutPendingIds(int limit) {
        LocalDateTime before = LocalDateTime.now().minusMinutes(payTimeoutMinutes);
        int cap = Math.max(1, Math.min(limit, 1000));
        List<Booking> list = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getBookingStatus, BookingStatus.PENDING_PAYMENT.getCode())
                .lt(Booking::getCreateTime, before)
                .orderByAsc(Booking::getCreateTime)       // 先到期的先处理
                .last("limit " + cap));
        List<Long> ids = new ArrayList<>(list.size());
        for (Booking b : list) {
            if (b.getBookingId() != null) {
                ids.add(b.getBookingId());
            }
        }
        return ids;
    }

    // =========================================================================
    // 内部方法
    // =========================================================================

    /**
     * ★ 按台账释放库存
     *
     * <p>顺序很重要：<b>先抢占式地标记 released，再还库存</b>。
     * 标记带 {@code released = 0} 前置条件，所以并发/重复调用时只有一个能成功，
     * 不会把库存加回去两次。这与 TaxiAgent 的「一次性令牌」是同一个思路。
     */
    private void releaseInventory(Long bookingId) {
        List<BookingInventoryLink> links = linkMapper.selectUnreleased(bookingId);
        List<BookingInventoryLink> reallyReleased = new ArrayList<>();
        for (BookingInventoryLink link : links) {
            int marked = linkMapper.markReleased(link.getId());
            if (marked == 0) {
                continue; // 已被别的流程释放过
            }
            int released = roomInventoryMapper.release(
                    link.getRoomTypeId(), link.getStayDate(), link.getRoomCount());
            if (released == 0) {
                // 台账与库存对不上：宁可失败也不要算出负数，但要告警
                log.error("库存释放异常（台账与库存不一致）: bookingId={}, roomTypeId={}, date={}, n={}",
                        bookingId, link.getRoomTypeId(), link.getStayDate(), link.getRoomCount());
            }
            reallyReleased.add(link);
        }

        // ★ Redis 侧等到【事务提交之后】再释放：
        //   提交成功 -> 释放；回滚 -> 什么都不做（DB 没变，Redis 也不该变）
        if (!reallyReleased.isEmpty()) {
            afterTransaction(() -> {
                for (BookingInventoryLink l : reallyReleased) {
                    inventoryCacheService.releaseOne(l.getRoomTypeId(), l.getStayDate(), l.getRoomCount());
                }
            }, null);
        }
    }

    /** 订单进入终态后从延迟队列移除（避免到点白跑一趟） */
    private void dequeueOnCommit(Long bookingId) {
        afterTransaction(() -> orderDelayQueue.remove(bookingId), null);
    }

    /**
     * 注册事务回调
     *
     * <p>本项目要同时维护两份库存（DB 与 Redis 缓存），这是典型的双写问题。
     * 处理原则：
     * <ul>
     *   <li><b>先占后查的写（下单/改期）</b>：Redis 预占在前，DB 在后；
     *       事务一旦没提交，就在 {@code onRollback} 里把 Redis 预占还回去。</li>
     *   <li><b>释放型的写（取消/失效/改期的旧日期）</b>：Redis 释放放在
     *       {@code onCommit} 里 —— DB 提交成功了才动缓存。</li>
     * </ul>
     *
     * <p>★ 为什么不用方法体内的 try/catch：那种写法只能覆盖"方法体抛异常"，
     * 覆盖不到"提交阶段才失败"（例如提交时死锁）。而 {@code afterCompletion}
     * 是在事务真正结束之后才回调的，两种情况都能兜住。
     *
     * @param onCommit   提交成功后执行（可为 null）
     * @param onRollback 回滚后执行（可为 null）
     */
    private void afterTransaction(Runnable onCommit, Runnable onRollback) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 没有活动事务（理论上不会发生，本类写方法都带 @Transactional）
            log.warn("当前没有活动事务，Redis 同步回调将立即执行 onCommit");
            if (onCommit != null) {
                onCommit.run();
            }
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        if (onCommit != null) {
                            onCommit.run();
                        }
                    } else {
                        if (onRollback != null) {
                            onRollback.run();
                        }
                    }
                } catch (Exception e) {
                    // 缓存同步失败不能影响已经提交的业务结果，只告警
                    log.error("Redis 库存同步回调失败（DB 结果不受影响）", e);
                }
            }
        });
    }

    /**
     * 距入住还有多少小时
     *
     * <p>基准是<b>入住日 00:00</b>，比较粗糙：真实酒店一般以入住当天某个时刻
     * （如 18:00）为免费取消截止点。P0 先用 00:00，后续可以做成可配置项。
     */
    private long hoursUntilCheckIn(LocalDate checkIn) {
        return ChronoUnit.HOURS.between(LocalDateTime.now(), checkIn.atStartOfDay());
    }

    /** 从订单快照里读政策（★ 保证政策改动不追溯老订单） */
    private SnapshotPolicy parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return new SnapshotPolicy("未知政策", List.of(new CancelPolicy.Tier(0, BigDecimal.ZERO)));
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String name = root.hasNonNull("name") ? root.get("name").asText() : "未知政策";
            List<CancelPolicy.Tier> tiers = new ArrayList<>();
            JsonNode arr = root.get("tiers");
            if (arr != null && arr.isArray()) {
                for (JsonNode t : arr) {
                    tiers.add(new CancelPolicy.Tier(
                            t.get("minHoursBefore").asInt(),
                            new BigDecimal(t.get("refundRatio").asText())));
                }
            }
            return new SnapshotPolicy(name, tiers);
        } catch (Exception e) {
            log.warn("解析取消政策快照失败，按不可退处理: json={}, err={}", json, e.getMessage());
            return new SnapshotPolicy("解析失败", List.of(new CancelPolicy.Tier(0, BigDecimal.ZERO)));
        }
    }

    /** 快照里的政策（minHoursBefore 降序） */
    private record SnapshotPolicy(String policyName, List<CancelPolicy.Tier> tiers) {

        BigDecimal refundRatio(long hoursBeforeCheckIn) {
            BigDecimal result = null;
            int bestMin = Integer.MIN_VALUE;
            for (CancelPolicy.Tier t : tiers) {
                if (hoursBeforeCheckIn >= t.minHoursBefore() && t.minHoursBefore() > bestMin) {
                    bestMin = t.minHoursBefore();
                    result = t.refundRatio();
                }
            }
            if (result != null) {
                return result;
            }
            // 与 CancelPolicy.refundRatio 同样的兜底：
            // 晚于所有门槛（入住当天/之后取消）时用最后一个档位，而不是返回 0。
            // 依赖快照里的 tiers 是按 minHoursBefore 降序生成的。
            return tiers.isEmpty() ? BigDecimal.ZERO : tiers.get(tiers.size() - 1).refundRatio();
        }
    }

    /**
     * 按 id 查酒店（查不到返回 null）
     *
     * <p>★ 防穿透：查不到的 id 记 60 秒。订单列表里如果引用了已删除的酒店，
     * 每次刷新列表都会对同一个 id 查一次库 —— 记下来就不用了。
     *
     * <p>注意这里<b>只记"不存在"，不缓存酒店本身</b>：写路径（下单）也用这个方法，
     * 拿到的必须是数据库里的最新数据。
     */
    private Hotel findHotel(Long hotelId) {
        if (hotelId == null) {
            return null;
        }
        if (absentCache.isHotelAbsent(hotelId)) {
            return null;
        }
        Hotel h = hotelMapper.selectOne(new LambdaQueryWrapper<Hotel>().eq(Hotel::getHotelId, hotelId));
        if (h == null) {
            absentCache.markHotelAbsent(hotelId);
        }
        return h;
    }

    private BookingVO toVO(Booking b) {
        Hotel hotel = b.getHotelId() == null ? null : findHotel(b.getHotelId());
        RoomType rt = b.getRoomTypeId() == null ? null : roomTypeMapper.selectOne(
                new LambdaQueryWrapper<RoomType>().eq(RoomType::getRoomTypeId, b.getRoomTypeId()));

        BookingStatus st = BookingStatus.fromCode(b.getBookingStatus());
        String policyName = parseSnapshot(b.getCancelPolicySnapshot()).policyName();

        return BookingVO.builder()
                .bookingId(b.getBookingId())
                .userId(b.getUserId())
                .hotelId(b.getHotelId())
                .hotelName(hotel == null ? null : hotel.getName())
                .roomTypeId(b.getRoomTypeId())
                .roomTypeName(rt == null ? null : rt.getName())
                .checkIn(b.getCheckIn())
                .checkOut(b.getCheckOut())
                .nights(b.getNights())
                .roomCount(b.getRoomCount())
                .guestCount(b.getGuestCount())
                .totalAmount(b.getTotalAmount())
                .paidAmount(b.getPaidAmount())
                .refundAmount(b.getRefundAmount())
                .bookingStatus(st.getCode())
                .statusDesc(st.getDesc())
                .cancelPolicy(rt == null ? null : rt.getCancelPolicy())
                .cancelPolicyDesc(policyName)
                .cancelRoleDesc(b.getCancelRole() == null ? null
                        : CancelRole.fromCode(b.getCancelRole()).getDesc())
                .cancelReason(b.getCancelReason())
                .createTime(b.getCreateTime())
                .payTime(b.getPayTime())
                .cancelTime(b.getCancelTime())
                .build();
    }

    private Booking requireBooking(Long bookingId) {
        if (bookingId == null) {
            throw BusinessException.badRequest("bookingId 不能为空");
        }
        // ★ 防穿透：这个 id 刚被查证过不存在，直接在缓存层拒掉，一次 DB 都不碰。
        //   订单号是雪花 ID，一旦"不存在"就基本不会再出现，记 60 秒是安全的。
        if (absentCache.isBookingAbsent(bookingId)) {
            throw BusinessException.notFound("订单不存在: " + bookingId);
        }
        Booking b = bookingMapper.selectOne(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getBookingId, bookingId));
        if (b == null) {
            absentCache.markBookingAbsent(bookingId);
            throw BusinessException.notFound("订单不存在: " + bookingId);
        }
        return b;
    }

    private RoomType requireRoomType(Long roomTypeId) {
        if (roomTypeId == null) {
            throw BusinessException.badRequest("roomTypeId 不能为空");
        }
        // ★ 防穿透：拿不存在的房型反复下单/报价，不该每次都查库
        if (absentCache.isRoomTypeAbsent(roomTypeId)) {
            throw BusinessException.notFound("房型不存在: " + roomTypeId);
        }
        RoomType rt = roomTypeMapper.selectOne(new LambdaQueryWrapper<RoomType>()
                .eq(RoomType::getRoomTypeId, roomTypeId));
        if (rt == null) {
            absentCache.markRoomTypeAbsent(roomTypeId);
            throw BusinessException.notFound("房型不存在: " + roomTypeId);
        }
        if (rt.getStatus() == null || rt.getStatus() != 1) {
            throw BusinessException.conflict("该房型已停售");
        }
        return rt;
    }

    /**
     * ★ 区间校验：左闭右开
     *
     * <p>{@code checkIn} 必须早于 {@code checkOut}；晚数 = 两者相差的天数。
     */
    private void validateRange(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null) {
            throw BusinessException.badRequest("入住日期和离店日期不能为空");
        }
        if (!checkIn.isBefore(checkOut)) {
            throw BusinessException.badRequest("离店日期必须晚于入住日期（区间左闭右开）");
        }
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights > MAX_NIGHTS) {
            throw BusinessException.badRequest("单次预订不能超过 " + MAX_NIGHTS + " 晚");
        }
        if (checkIn.isBefore(LocalDate.now())) {
            throw BusinessException.badRequest("入住日期不能早于今天");
        }
    }

    /** 展开入住区间 [checkIn, checkOut) 的每一天 */
    private List<LocalDate> stayDates(LocalDate checkIn, LocalDate checkOut) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = checkIn; d.isBefore(checkOut); d = d.plusDays(1)) {
            dates.add(d);
        }
        return dates;
    }

    private int normalizeRoomCount(Integer roomCount) {
        int n = roomCount == null ? 1 : roomCount;
        if (n < 1) {
            throw BusinessException.badRequest("间数至少为 1");
        }
        if (n > MAX_ROOMS) {
            throw BusinessException.badRequest("单次最多预订 " + MAX_ROOMS + " 间");
        }
        return n;
    }

    private boolean isWeekend(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
    }
}
