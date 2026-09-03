package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.pooltable.TableSessionSummaryDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.*;
import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SessionServiceImpl implements SessionService {

    private final TableSessionRepository tableSessionRepository;
    private final SessionSegmentRepository sessionSegmentRepository;
    private final SessionPauseRepository sessionPauseRepository;
    private final BillRepository billRepository;
    private final BillLineRepository billLineRepository;
    private final PoolTableRepository poolTableRepository;
    private final PoolTableRateRepository poolTableRateRepository;
    private final CustomerTypeRepository customerTypeRepository;
    private final BranchContext branchContext;
    private final AuditService auditService;

    public SessionServiceImpl(TableSessionRepository tableSessionRepository,
                              SessionSegmentRepository sessionSegmentRepository,
                              SessionPauseRepository sessionPauseRepository,
                              BillRepository billRepository,
                              BillLineRepository billLineRepository,
                              PoolTableRepository poolTableRepository,
                              PoolTableRateRepository poolTableRateRepository,
                              CustomerTypeRepository customerTypeRepository,
                              BranchContext branchContext,
                              AuditService auditService) {
        this.tableSessionRepository = tableSessionRepository;
        this.sessionSegmentRepository = sessionSegmentRepository;
        this.sessionPauseRepository = sessionPauseRepository;
        this.billRepository = billRepository;
        this.billLineRepository = billLineRepository;
        this.poolTableRepository = poolTableRepository;
        this.poolTableRateRepository = poolTableRateRepository;
        this.customerTypeRepository = customerTypeRepository;
        this.branchContext = branchContext;
        this.auditService = auditService;
    }


    @Override
    @Transactional
    public SessionResponseDTO openSession(OpenSessionRequestDTO openSessionRequestDTO) {
        PoolTable poolTable = poolTableRepository.findById(openSessionRequestDTO.getTableId())
                .orElseThrow(() -> new ResourceNotFoundException("Table", openSessionRequestDTO.getTableId()));
        if (poolTable.getArchivedAt() != null || !Boolean.TRUE.equals(poolTable.getIsActive())) {
            throw new BusinessRuleException("Table '" + poolTable.getName() + "' is not available.");
        }

        CustomerType customerType = customerTypeRepository.findById(openSessionRequestDTO.getCustomerTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer type", openSessionRequestDTO.getCustomerTypeId()));
        if (customerType.getArchivedAt() != null) {
            throw new BusinessRuleException("Customer type '" + customerType.getName() + "' is archived.");
        }

        BigDecimal standardRate = poolTableRateRepository.findCurrentByPoolTableId(poolTable.getId())
                .map(PoolTableRate::getRatePerMinute)
                .orElseThrow(() -> new BusinessRuleException(
                        "Table '" + poolTable.getName() + "' has no rate in force and cannot be opened."));

        BigDecimal overrideRate = openSessionRequestDTO.getRateOverridePerMinute();
        if (overrideRate != null && !Boolean.TRUE.equals(customerType.getAllowsRateOverride())) {
            throw new BusinessRuleException(
                    "Customer type '" + customerType.getName() + "' does not allow a rate override.");
        }
        // Any value, no floor and no approval — the owner's rule. Detection, not prevention:
        // what makes that safe is that the override, the standard rate and the actor are all
        // recorded, so the foregone revenue is arithmetic.
        BigDecimal effectiveRate = overrideRate != null ? overrideRate : standardRate;

        UUID branchId = branchContext.getCurrentBranchId();
        UUID actorId = branchContext.getCurrentUserId();

        Bill bill = new Bill();
        bill.setBranchId(branchId);
        bill.setStatus(BillStatus.OPEN);
        bill.setCustomerTypeId(customerType.getId());
        bill.setOpenedBy(actorId);
        bill.setSubtotalTime(BigDecimal.ZERO);
        bill.setSubtotalItems(BigDecimal.ZERO);
        bill.setTotalAmount(BigDecimal.ZERO);
        bill.setTotalCost(BigDecimal.ZERO);
        // Flushed in order: these tables are joined by plain uuid columns, so Hibernate cannot
        // see the dependency and would be free to insert the child first.
        Bill savedBill = billRepository.saveAndFlush(bill);

        TableSession session = new TableSession();
        session.setBranchId(branchId);
        session.setBillId(savedBill.getId());
        session.setPoolTableId(poolTable.getId());
        session.setCustomerTypeId(customerType.getId());
        session.setStatus(SessionStatus.OPEN);
        session.setOpenedBy(actorId);
        session.setNeedsReview(false);
        session.setStandardRatePerMinute(standardRate);
        if (overrideRate != null) {
            session.setRateOverridePerMinute(overrideRate);
            session.setRateOverrideBy(actorId);
            session.setRateOverrideReason(openSessionRequestDTO.getRateOverrideReason());
        }
        // saveAndFlush so table_session_one_open_per_table_key fires here, as a clean 409,
        // rather than at commit. The index is the check — there is deliberately no pre-select,
        // which would only be a race with a second counter tab.
        TableSession savedSession = tableSessionRepository.saveAndFlush(session);

        SessionSegment segment = new SessionSegment();
        segment.setBranchId(branchId);
        segment.setSessionId(savedSession.getId());
        segment.setPoolTableId(poolTable.getId());
        segment.setSeq(1);
        segment.setRatePerMinute(effectiveRate);
        segment.setStartedAt(savedSession.getOpenedAt());
        sessionSegmentRepository.saveAndFlush(segment);

        if (overrideRate != null) {
            auditService.record("SESSION_RATE_OVERRIDE", "table_session", savedSession.getId(),
                    rateSnapshot(standardRate), rateSnapshot(overrideRate),
                    openSessionRequestDTO.getRateOverrideReason());
        }

        return toResponseDto(savedSession, OffsetDateTime.now());
    }

    @Override
    @Transactional(readOnly = true)
    public SessionResponseDTO getSession(UUID id) {
        return toResponseDto(requireSession(id), OffsetDateTime.now());
    }

    @Override
    @Transactional
    public SessionResponseDTO pauseSession(UUID id, PauseSessionRequestDTO pauseSessionRequestDTO) {
        TableSession session = requireSession(id);
        requireLive(session);
        if (session.getStatus() == SessionStatus.PAUSED) {
            throw new BusinessRuleException("Session is already paused.");
        }

        OffsetDateTime now = OffsetDateTime.now();

        SessionPause pause = new SessionPause();
        pause.setBranchId(session.getBranchId());
        pause.setSessionId(session.getId());
        pause.setPausedAt(now);
        pause.setPausedBy(branchContext.getCurrentUserId());
        pause.setReason(pauseSessionRequestDTO == null ? null : pauseSessionRequestDTO.getReason());
        sessionPauseRepository.saveAndFlush(pause);

        session.setStatus(SessionStatus.PAUSED);
        tableSessionRepository.saveAndFlush(session);

        return toResponseDto(session, now);
    }

    @Override
    @Transactional
    public SessionResponseDTO resumeSession(UUID id) {
        TableSession session = requireSession(id);
        requireLive(session);

        SessionPause pause = sessionPauseRepository.findOpenBySessionId(id)
                .orElseThrow(() -> new BusinessRuleException("Session is not paused."));

        OffsetDateTime now = OffsetDateTime.now();
        pause.setResumedAt(now);
        pause.setResumedBy(branchContext.getCurrentUserId());
        sessionPauseRepository.saveAndFlush(pause);

        session.setStatus(SessionStatus.OPEN);
        tableSessionRepository.saveAndFlush(session);

        return toResponseDto(session, now);
    }

    @Override
    @Transactional
    public SessionResponseDTO closeSession(UUID id) {
        TableSession session = requireSession(id);
        requireLive(session);
        return close(session, OffsetDateTime.now(), SessionCloseKind.MANUAL, false);
    }

    @Override
    @Transactional
    public List<SessionResponseDTO> autoCloseOpenSessions(OffsetDateTime cutoff) {
        return tableSessionRepository.findAllLive()
                .stream()
                // A session opened after the cutoff belongs to the next business day, and
                // capping it would put its end before its start.
                .filter(session -> session.getOpenedAt().isBefore(cutoff))
                .map(session -> close(session, cutoff, SessionCloseKind.AUTO_END_OF_DAY, true))
                .toList();
    }

    // Shared by the manual close and the end-of-day safety net. The only differences are the
    // instant time stops, how the close is labelled, and whether a human must look at it.
    private SessionResponseDTO close(TableSession session, OffsetDateTime closedAt,
                                     SessionCloseKind closeKind, boolean needsReview) {
        UUID id = session.getId();
        OffsetDateTime now = closedAt;
        UUID actorId = branchContext.getCurrentUserId();

        // Closing while paused is allowed; the open pause ends at the close instant so the
        // paused stretch is deducted rather than silently billed.
        sessionPauseRepository.findOpenBySessionId(id).ifPresent(pause -> {
            pause.setResumedAt(now);
            pause.setResumedBy(actorId);
            sessionPauseRepository.saveAndFlush(pause);
        });

        List<SessionSegment> segments = sessionSegmentRepository.findBySessionId(id);
        for (SessionSegment segment : segments) {
            if (segment.getEndedAt() == null) {
                segment.setEndedAt(now);
                sessionSegmentRepository.saveAndFlush(segment);
            }
        }

        List<SessionPause> pauses = sessionPauseRepository.findBySessionId(id);
        String poolTableName = poolTableNames().getOrDefault(session.getPoolTableId(), "Table");

        int totalMinutes = 0;
        BigDecimal totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        int seq = billLineRepository.findMaxSeq(session.getBillId());

        // One TIME line per segment, so a transferred session shows both tables and both
        // rates. With no transfers built yet there is exactly one, but the shape is what
        // makes transfer an addition later rather than a rewrite.
        for (SessionSegment segment : segments) {
            int minutes = billedMinutesFor(segment, now, pauses);
            BigDecimal amount = amountFor(segment.getRatePerMinute(), minutes);
            totalMinutes += minutes;
            totalAmount = totalAmount.add(amount);

            BillLine line = new BillLine();
            line.setBranchId(session.getBranchId());
            line.setBillId(session.getBillId());
            line.setLineKind(BillLineKind.TIME);
            line.setSeq(++seq);
            line.setSessionId(session.getId());
            line.setDescription(timeLineDescription(poolTableName, minutes, segment.getRatePerMinute()));
            // quantity 1 and the whole charge as unit_price, not minutes x rate: unit_price is
            // numeric(12,2) and a rate can carry four decimals (PHP 200/hour is 3.3333/min),
            // so pricing per minute here would round the rate away and undercharge.
            line.setQuantity(BigDecimal.ONE);
            line.setUnitPrice(amount);
            line.setUnitCost(BigDecimal.ZERO);
            line.setBilledMinutes(minutes);
            line.setCreatedBy(actorId);
            billLineRepository.saveAndFlush(line);
        }

        session.setBilledMinutes(totalMinutes);
        session.setTimeAmount(totalAmount);
        session.setStatus(closeKind == SessionCloseKind.AUTO_END_OF_DAY
                ? SessionStatus.AUTO_CLOSED : SessionStatus.CLOSED);
        session.setClosedAt(now);
        session.setClosedBy(actorId);
        session.setCloseKind(closeKind);
        session.setNeedsReview(needsReview);
        tableSessionRepository.saveAndFlush(session);

        return toResponseDto(session, now);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TableSessionSummaryDTO> getLiveSessionSummariesByTable() {
        List<TableSession> live = tableSessionRepository.findAllLive();
        if (live.isEmpty()) {
            return Map.of();
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<UUID> sessionIds = live.stream().map(TableSession::getId).toList();
        List<UUID> billIds = live.stream().map(TableSession::getBillId).toList();

        // Batched: the floor view is the most-polled endpoint in the building, so it must not
        // issue a query per occupied table.
        Map<UUID, List<SessionSegment>> segmentsBySession = sessionSegmentRepository.findBySessionIdIn(sessionIds)
                .stream()
                .collect(Collectors.groupingBy(SessionSegment::getSessionId));
        Map<UUID, List<SessionPause>> pausesBySession = sessionPauseRepository.findBySessionIdIn(sessionIds)
                .stream()
                .collect(Collectors.groupingBy(SessionPause::getSessionId));
        Map<UUID, BigDecimal> itemTotals = itemTotalsByBill(billIds);
        Map<UUID, String> customerTypeNames = customerTypeNames();
        Map<UUID, String> tableNames = poolTableNames();

        Map<UUID, TableSessionSummaryDTO> byTable = new LinkedHashMap<>();
        for (TableSession session : live) {
            List<SessionSegment> segments = segmentsBySession.getOrDefault(session.getId(), List.of());
            List<SessionPause> pauses = pausesBySession.getOrDefault(session.getId(), List.of());

            byTable.put(session.getPoolTableId(), new TableSessionSummaryDTO(
                    session.getId(),
                    session.getBillId(),
                    tableNames.get(session.getPoolTableId()),
                    session.getStatus(),
                    session.getCustomerTypeId(),
                    customerTypeNames.get(session.getCustomerTypeId()),
                    session.getOpenedAt(),
                    runningMinutes(segments, pauses, now),
                    runningSeconds(segments, pauses, now),
                    effectiveRateOf(segments, session),
                    runningAmount(segments, pauses, now),
                    billLineRepository.sumLiveProductQuantity(List.of(session.getBillId())),
                    itemTotals.getOrDefault(session.getBillId(), BigDecimal.ZERO),
                    runningAmount(segments, pauses, now)
                            .add(itemTotals.getOrDefault(session.getBillId(), BigDecimal.ZERO))));
        }
        return byTable;
    }


    @Override
    @Transactional(readOnly = true)
    public List<TableSessionSummaryDTO> getSummariesForBill(UUID billId) {
        OffsetDateTime now = OffsetDateTime.now();
        Map<UUID, String> customerTypeNames = customerTypeNames();

        return tableSessionRepository.findByBillId(billId)
                .stream()
                .map(session -> toSummaryDto(session, customerTypeNames, BigDecimal.ZERO, now))
                .toList();
    }


    // ---- billing -------------------------------------------------------------------
    //
    // Billed minutes = segment duration minus any pause overlapping it, floored to whole
    // minutes. Floored, never rounded up: the owner sells exact-minute time with no minimum,
    // so 90m59s is 90 minutes. Computed per segment because each segment has its own rate and
    // its own TIME line.

    private int billedMinutesFor(SessionSegment segment, OffsetDateTime asOf, List<SessionPause> pauses) {
        return (int) (billedSecondsFor(segment, asOf, pauses) / 60L);
    }

    // The same computation, stopping one step earlier. The minute figure above is this
    // divided by sixty; keeping the remainder costs nothing and is what lets the client
    // animate a counter that agrees with the server continuously rather than in arrears.
    private long billedSecondsFor(SessionSegment segment, OffsetDateTime asOf, List<SessionPause> pauses) {
        OffsetDateTime start = segment.getStartedAt();
        OffsetDateTime end = segment.getEndedAt() != null ? segment.getEndedAt() : asOf;
        if (!end.isAfter(start)) {
            return 0;
        }

        long seconds = Duration.between(start, end).toSeconds();
        for (SessionPause pause : pauses) {
            // Clamped to this segment: a pause that spans a transfer is deducted from each
            // segment only for the part that actually overlaps it.
            OffsetDateTime pauseStart = latest(pause.getPausedAt(), start);
            OffsetDateTime pauseEnd = earliest(pause.getResumedAt() != null ? pause.getResumedAt() : asOf, end);
            if (pauseEnd.isAfter(pauseStart)) {
                seconds -= Duration.between(pauseStart, pauseEnd).toSeconds();
            }
        }

        return Math.max(seconds, 0L);
    }

    private BigDecimal amountFor(BigDecimal ratePerMinute, int minutes) {
        return ratePerMinute.multiply(BigDecimal.valueOf(minutes)).setScale(2, RoundingMode.HALF_UP);
    }

    private int runningMinutes(List<SessionSegment> segments, List<SessionPause> pauses, OffsetDateTime now) {
        int minutes = 0;
        for (SessionSegment segment : segments) {
            minutes += billedMinutesFor(segment, now, pauses);
        }
        return minutes;
    }

    private int runningSeconds(List<SessionSegment> segments, List<SessionPause> pauses, OffsetDateTime now) {
        long seconds = 0L;
        for (SessionSegment segment : segments) {
            seconds += billedSecondsFor(segment, now, pauses);
        }
        return (int) seconds;
    }

    private BigDecimal runningAmount(List<SessionSegment> segments, List<SessionPause> pauses, OffsetDateTime now) {
        BigDecimal amount = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (SessionSegment segment : segments) {
            amount = amount.add(amountFor(segment.getRatePerMinute(), billedMinutesFor(segment, now, pauses)));
        }
        return amount;
    }


    // ---- mapping -------------------------------------------------------------------

    private SessionResponseDTO toResponseDto(TableSession session, OffsetDateTime now) {
        List<SessionSegment> segments = sessionSegmentRepository.findBySessionId(session.getId());
        List<SessionPause> pauses = sessionPauseRepository.findBySessionId(session.getId());
        Map<UUID, String> tableNames = poolTableNames();

        List<SessionSegmentResponseDTO> segmentDtos = segments.stream()
                .map(segment -> {
                    int minutes = billedMinutesFor(segment, now, pauses);
                    return new SessionSegmentResponseDTO(
                            segment.getId(),
                            segment.getPoolTableId(),
                            tableNames.get(segment.getPoolTableId()),
                            segment.getSeq(),
                            segment.getRatePerMinute(),
                            segment.getStartedAt(),
                            segment.getEndedAt(),
                            minutes,
                            amountFor(segment.getRatePerMinute(), minutes));
                })
                .toList();

        List<SessionPauseResponseDTO> pauseDtos = pauses.stream()
                .map(pause -> new SessionPauseResponseDTO(
                        pause.getId(), pause.getPausedAt(), pause.getResumedAt(), pause.getReason()))
                .toList();

        // Once closed the stored figures are authoritative: the receipt and the reports must
        // never recompute and never disagree. While live there is nothing stored yet, so the
        // running figure is computed here.
        Integer billedMinutes = session.getBilledMinutes() != null
                ? session.getBilledMinutes()
                : runningMinutes(segments, pauses, now);
        BigDecimal timeAmount = session.getTimeAmount() != null
                ? session.getTimeAmount()
                : runningAmount(segments, pauses, now);

        // A closed session's stored minutes are authoritative, so its seconds are reported as
        // exactly that many minutes: there is nothing left running to be precise about.
        int billedSeconds = session.getBilledMinutes() != null
                ? session.getBilledMinutes() * 60
                : runningSeconds(segments, pauses, now);

        BigDecimal itemTotal = billLineRepository.sumLiveProductTotals(session.getBillId());

        return new SessionResponseDTO(
                session.getId(),
                session.getBillId(),
                session.getPoolTableId(),
                tableNames.get(session.getPoolTableId()),
                session.getCustomerTypeId(),
                customerTypeNames().get(session.getCustomerTypeId()),
                session.getStatus(),
                session.getOpenedAt(),
                session.getClosedAt(),
                session.getCloseKind(),
                session.getStandardRatePerMinute(),
                session.getRateOverridePerMinute(),
                billedMinutes,
                billedSeconds,
                timeAmount,
                billLineRepository.sumLiveProductQuantity(List.of(session.getBillId())),
                itemTotal,
                timeAmount.add(itemTotal),
                segmentDtos,
                pauseDtos,
                now);
    }

    // A closed session reports what was stored; a live one reports the running figure.
    private TableSessionSummaryDTO toSummaryDto(TableSession session, Map<UUID, String> customerTypeNames,
                                                BigDecimal itemTotal, OffsetDateTime now) {
        List<SessionSegment> segments = sessionSegmentRepository.findBySessionId(session.getId());
        List<SessionPause> pauses = sessionPauseRepository.findBySessionId(session.getId());

        return new TableSessionSummaryDTO(
                session.getId(),
                session.getBillId(),
                poolTableNames().get(session.getPoolTableId()),
                session.getStatus(),
                session.getCustomerTypeId(),
                customerTypeNames.get(session.getCustomerTypeId()),
                session.getOpenedAt(),
                session.getBilledMinutes() != null ? session.getBilledMinutes() : runningMinutes(segments, pauses, now),
                session.getBilledMinutes() != null ? session.getBilledMinutes() * 60 : runningSeconds(segments, pauses, now),
                effectiveRateOf(segments, session),
                session.getTimeAmount() != null ? session.getTimeAmount() : runningAmount(segments, pauses, now),
                billLineRepository.sumLiveProductQuantity(List.of(session.getBillId())),
                itemTotal,
                (session.getTimeAmount() != null ? session.getTimeAmount() : runningAmount(segments, pauses, now))
                        .add(itemTotal));
    }

    private TableSession requireSession(UUID id) {
        return tableSessionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Session", id));
    }

    private void requireLive(TableSession session) {
        if (session.getStatus() != SessionStatus.OPEN && session.getStatus() != SessionStatus.PAUSED) {
            throw new BusinessRuleException("Session is already " + session.getStatus() + ".");
        }
    }

    private BigDecimal effectiveRateOf(List<SessionSegment> segments, TableSession session) {
        return segments.stream()
                .filter(segment -> segment.getEndedAt() == null)
                .findFirst()
                .map(SessionSegment::getRatePerMinute)
                .orElseGet(session::getStandardRatePerMinute);
    }

    private Map<UUID, BigDecimal> itemTotalsByBill(List<UUID> billIds) {
        Map<UUID, BigDecimal> totals = new HashMap<>();
        for (Object[] row : billLineRepository.sumLiveProductTotalsByBill(billIds)) {
            totals.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return totals;
    }

    private Map<UUID, String> poolTableNames() {
        return poolTableRepository.findAll()
                .stream()
                .collect(Collectors.toMap(PoolTable::getId, PoolTable::getName));
    }

    private Map<UUID, String> customerTypeNames() {
        return customerTypeRepository.findAll()
                .stream()
                .collect(Collectors.toMap(CustomerType::getId, CustomerType::getName));
    }

    // The rate is shown at two decimals; it is BILLED at four. This string is a customer-facing
    // label snapshotted onto the line, not an input to any sum — the charge comes from the rate
    // on session_segment, which keeps every one of its four decimals. A P200/hour table bills at
    // 3.3333/min and reads "@ 3.33/min".
    /*
     * Charging less time than was played.
     *
     * The session's own billedMinutes and every session_segment stay exactly as they were —
     * that is the record of what physically happened at the table. What changes is the CHARGE:
     * the TIME lines are re-priced to the reduced figure, apportioned across segments in the
     * ratio of the minutes each actually ran, so a session that spanned a rate change keeps
     * each segment's own snapshotted rate. The remainder lands on the last segment, so the
     * apportioned minutes sum to exactly what was asked for rather than to a rounding of it.
     */
    @Override
    @Transactional
    public SessionResponseDTO overrideBilledMinutes(UUID sessionId,
                                                    BilledMinutesOverrideRequestDTO request) {
        TableSession session = tableSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));

        if (session.getBilledMinutes() == null) {
            throw new BusinessRuleException(
                    "That session has not been closed yet, so there is no time to reduce.");
        }
        Bill bill = billRepository.findById(session.getBillId())
                .orElseThrow(() -> new ResourceNotFoundException("Bill", session.getBillId()));
        if (bill.getStatus() != BillStatus.OPEN) {
            throw new BusinessRuleException("That bill is already " + bill.getStatus()
                    + "; the time charged can no longer be changed.");
        }

        int actual = session.getBilledMinutes();
        int charged = request.getBilledMinutes();
        if (charged > actual) {
            throw new BusinessRuleException("That session ran " + actual + " minutes. Charging "
                    + charged + " would be more than was played, which is an overcharge rather "
                    + "than a discount.");
        }

        List<SessionSegment> segments = sessionSegmentRepository.findBySessionId(sessionId);
        List<BillLine> timeLines = billLineRepository.findByBillId(bill.getId()).stream()
                .filter(line -> line.getLineKind() == BillLineKind.TIME
                        && sessionId.equals(line.getSessionId())
                        && line.getVoidedAt() == null)
                .sorted(java.util.Comparator.comparing(BillLine::getSeq))
                .toList();
        if (timeLines.isEmpty()) {
            throw new BusinessRuleException("That session has no time on the bill to reduce.");
        }

        String poolTableName = poolTableNames().getOrDefault(session.getPoolTableId(), "Table");
        UUID actorId = branchContext.getCurrentUserId();

        int assigned = 0;
        BigDecimal newTotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (int i = 0; i < timeLines.size(); i++) {
            BillLine line = timeLines.get(i);
            int lineActual = line.getBilledMinutes() == null ? 0 : line.getBilledMinutes();
            int lineCharged = (i == timeLines.size() - 1)
                    ? charged - assigned
                    : lineShare(lineActual, charged, actual);
            assigned += lineCharged;

            // The TIME lines were written one per segment, in segment order, with increasing
            // seq — so index i is segment i, and each keeps the rate it was snapshotted at.
            BigDecimal rate = i < segments.size()
                    ? segments.get(i).getRatePerMinute()
                    : session.getStandardRatePerMinute();
            BigDecimal amount = amountFor(rate, lineCharged);
            newTotal = newTotal.add(amount);

            line.setBilledMinutes(lineCharged);
            line.setUnitPrice(amount);
            // The adjustment is stated on the line, not hidden in it: whoever reads this bill
            // or its receipt sees both what was played and what was charged.
            line.setDescription(timeLineDescription(poolTableName, lineCharged, rate)
                    + " (reduced from " + lineActual + " min)");
            billLineRepository.saveAndFlush(line);
        }

        session.setBilledMinutesOverride(charged);
        session.setBilledMinutesOverrideBy(actorId);
        session.setBilledMinutesOverrideReason(request.getReason().trim());
        session.setTimeAmount(newTotal);
        TableSession saved = tableSessionRepository.saveAndFlush(session);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("billedMinutes", actual);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("billedMinutesCharged", charged);
        after.put("timeAmount", newTotal);
        auditService.record("SESSION_TIME_REDUCED", "table_session", saved.getId(),
                before, after, request.getReason().trim());

        return toResponseDto(saved, OffsetDateTime.now());
    }

    // Proportional share of the reduced total for one segment, before the remainder lands on
    // the last one.
    private int lineShare(int lineActual, int charged, int actual) {
        if (actual <= 0) return 0;
        return (int) Math.floor((double) lineActual * charged / actual);
    }

    private String timeLineDescription(String poolTableName, int minutes, BigDecimal ratePerMinute) {
        return poolTableName + " - " + minutes + " min @ "
                + ratePerMinute.setScale(2, RoundingMode.HALF_UP).toPlainString() + "/min";
    }

    private Map<String, Object> rateSnapshot(BigDecimal ratePerMinute) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ratePerMinute", ratePerMinute);
        return snapshot;
    }

    private static OffsetDateTime latest(OffsetDateTime a, OffsetDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static OffsetDateTime earliest(OffsetDateTime a, OffsetDateTime b) {
        return a.isBefore(b) ? a : b;
    }
}
