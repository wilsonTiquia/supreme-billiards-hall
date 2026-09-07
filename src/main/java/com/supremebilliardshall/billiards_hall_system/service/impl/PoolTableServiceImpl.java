package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.pooltable.FloorViewResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.PoolTableRateRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.TableSessionSummaryDTO;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.PoolTableRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.PoolTableResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.PoolTable;
import com.supremebilliardshall.billiards_hall_system.entity.PoolTableRate;
import com.supremebilliardshall.billiards_hall_system.exception.DuplicateResourceException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceInUseException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.mapper.PoolTableMapper;
import com.supremebilliardshall.billiards_hall_system.repository.PoolTableRateRepository;
import com.supremebilliardshall.billiards_hall_system.repository.PoolTableRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.PoolTableService;
import com.supremebilliardshall.billiards_hall_system.service.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PoolTableServiceImpl implements PoolTableService {

    private final PoolTableRepository poolTableRepository;
    private final PoolTableRateRepository poolTableRateRepository;
    private final PoolTableMapper poolTableMapper;
    private final BranchContext branchContext;
    private final AuditService auditService;
    private final SessionService sessionService;

    public PoolTableServiceImpl(PoolTableRepository poolTableRepository,
                                PoolTableRateRepository poolTableRateRepository,
                                PoolTableMapper poolTableMapper,
                                BranchContext branchContext,
                                AuditService auditService,
                                SessionService sessionService) {
        this.poolTableRepository = poolTableRepository;
        this.poolTableRateRepository = poolTableRateRepository;
        this.poolTableMapper = poolTableMapper;
        this.branchContext = branchContext;
        this.auditService = auditService;
        this.sessionService = sessionService;
    }


    @Override
    @Transactional(readOnly = true)
    public FloorViewResponseDTO getFloorView() {
        // One query for every current rate, and one for every live session, rather than one
        // per table: this is the screen the counter leaves open all night.
        Map<UUID, PoolTableRate> currentRates = poolTableRateRepository.findAllCurrent()
                .stream()
                .collect(Collectors.toMap(PoolTableRate::getPoolTableId, Function.identity()));
        Map<UUID, TableSessionSummaryDTO> liveSessions = sessionService.getLiveSessionSummariesByTable();

        List<PoolTableResponseDTO> tables = poolTableRepository.findAllActive()
                .stream()
                .map(table -> {
                    PoolTableResponseDTO responseDto = toResponseDto(table, currentRates.get(table.getId()));
                    responseDto.setSession(liveSessions.get(table.getId()));
                    return responseDto;
                })
                .toList();

        return new FloorViewResponseDTO(tables, OffsetDateTime.now());
    }

    @Override
    @Transactional
    public PoolTableResponseDTO createTable(PoolTableRequestDTO poolTableRequestDTO) {
        boolean exists = poolTableRepository.existsByName(poolTableRequestDTO.getName());
        if (exists) {
            throw new DuplicateResourceException("Table with name '" + poolTableRequestDTO.getName() + "' already exists.");
        }

        PoolTable poolTable = poolTableMapper.toEntity(poolTableRequestDTO);
        poolTable.setBranchId(branchContext.getCurrentBranchId());
        if (poolTable.getIsActive() == null) {
            poolTable.setIsActive(true);
        }

        PoolTable savedTable = poolTableRepository.save(poolTable);
        // Not routed through changeRate, unlike an update: there is no period to close, and a
        // POOL_TABLE_RATE_CHANGED row for a table that never had a rate would read as a reprice.
        PoolTableRate rate = openRatePeriod(savedTable.getId(),
                poolTableRequestDTO.getRatePerMinute(), poolTableRequestDTO.getRatePerHour(),
                OffsetDateTime.now());

        auditService.record("POOL_TABLE_CREATED", "pool_table", savedTable.getId(),
                null, auditSnapshot(savedTable, rate), null);

        return toResponseDto(savedTable, rate);
    }

    @Override
    @Transactional
    public PoolTableResponseDTO updateTable(UUID id, PoolTableRequestDTO poolTableRequestDTO) {
        PoolTable existing = poolTableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Table", id));

        // check if the name is the same
        boolean existingName = poolTableRepository.
                existsByNameAndIdNot(poolTableRequestDTO.getName(), id);

        if (existingName) {
            throw new DuplicateResourceException("Table with name '" + poolTableRequestDTO.getName() + "' already exists.");
        }

        Map<String, Object> before = auditSnapshot(existing, currentRateOf(existing.getId()));
        Boolean isActive = existing.getIsActive();
        poolTableMapper.updateEntityFromDto(poolTableRequestDTO, existing);
        if (existing.getIsActive() == null) {
            existing.setIsActive(isActive);
        }

        PoolTable updated = poolTableRepository.save(existing);

        // A changed rate goes through the same close-and-open path as PUT /rate, so the
        // invariant lives in one place.
        //
        // Both figures are compared, not just the per-minute one. A table stored at 4.0000/min
        // that the admin re-enters as PHP 240/hour is the same rate said differently, and still
        // a change: the hourly figure is what the screen reads back. Comparing only the derived
        // rate would leave that edit silently discarded — and comparing only the hourly one
        // would open a new period every time a rename posted the form back.
        PoolTableRate currentRate = currentRateOf(id);
        BigDecimal requestedRate = RateConversion.perMinuteFrom(poolTableRequestDTO.getRatePerMinute(),
                poolTableRequestDTO.getRatePerHour());
        boolean rateChanged = currentRate == null
                || currentRate.getRatePerMinute().compareTo(requestedRate) != 0
                || !sameFigure(currentRate.getRatePerHour(), poolTableRequestDTO.getRatePerHour());

        if (rateChanged) {
            // changeRate writes its own POOL_TABLE_RATE_CHANGED row; this one records the rest
            // of the edit, so a rename that also repriced leaves both facts on the log.
            auditService.record("POOL_TABLE_UPDATED", "pool_table", updated.getId(),
                    before, auditSnapshot(updated, requestedRate, poolTableRequestDTO.getRatePerHour()), null);
            return changeRate(id, new PoolTableRateRequestDTO(poolTableRequestDTO.getRatePerMinute(),
                    poolTableRequestDTO.getRatePerHour(), null));
        }

        auditService.record("POOL_TABLE_UPDATED", "pool_table", updated.getId(),
                before, auditSnapshot(updated, currentRate), null);
        return toResponseDto(updated, currentRate);
    }

    @Override
    @Transactional
    public PoolTableResponseDTO changeRate(UUID id, PoolTableRateRequestDTO poolTableRateRequestDTO) {
        PoolTable poolTable = poolTableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Table", id));

        OffsetDateTime effectiveFrom = poolTableRateRequestDTO.getEffectiveFrom() != null
                ? poolTableRateRequestDTO.getEffectiveFrom()
                : OffsetDateTime.now();

        BigDecimal previousRatePerMinute = null;
        BigDecimal previousRatePerHour = null;
        PoolTableRate current = poolTableRateRepository.findCurrentByPoolTableId(id).orElse(null);
        if (current != null) {
            previousRatePerMinute = current.getRatePerMinute();
            previousRatePerHour = current.getRatePerHour();
            current.setEffectiveTo(effectiveFrom);
            // Flushed before the insert: Hibernate orders inserts ahead of updates, and the
            // pool_table_rate_no_overlap exclusion constraint is not deferrable.
            poolTableRateRepository.saveAndFlush(current);
        }

        PoolTableRate rate = openRatePeriod(id, poolTableRateRequestDTO.getRatePerMinute(),
                poolTableRateRequestDTO.getRatePerHour(), effectiveFrom);

        auditService.record("POOL_TABLE_RATE_CHANGED", "pool_table_rate", rate.getId(),
                rateSnapshot(previousRatePerMinute, previousRatePerHour, null),
                rateSnapshot(rate.getRatePerMinute(), rate.getRatePerHour(), effectiveFrom),
                "Rate change for table " + poolTable.getName());

        return toResponseDto(poolTable, rate);
    }

    @Override
    @Transactional
    public void deleteTable(UUID id) {
        PoolTable poolTable = poolTableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Table", id));

        if (poolTableRepository.countOpenSessions(id) > 0) {
            throw new ResourceInUseException("Table '" + poolTable.getName() + "' has an open session and cannot be archived.");
        }

        Map<String, Object> before = auditSnapshot(poolTable, currentRateOf(poolTable.getId()));
        poolTable.setArchivedAt(OffsetDateTime.now());
        PoolTable archived = poolTableRepository.save(poolTable);
        auditService.record("POOL_TABLE_ARCHIVED", "pool_table", archived.getId(),
                before, auditSnapshot(archived, currentRateOf(archived.getId())), null);
    }

    private Map<String, Object> auditSnapshot(PoolTable table, PoolTableRate rate) {
        return auditSnapshot(table,
                rate == null ? null : rate.getRatePerMinute(),
                rate == null ? null : rate.getRatePerHour());
    }

    private Map<String, Object> auditSnapshot(PoolTable table, BigDecimal ratePerMinute, BigDecimal ratePerHour) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", table.getName());
        snapshot.put("tableNumber", table.getTableNumber());
        snapshot.put("ratePerMinute", ratePerMinute);
        snapshot.put("ratePerHour", ratePerHour);
        snapshot.put("isActive", table.getIsActive());
        snapshot.put("archivedAt", table.getArchivedAt());
        return snapshot;
    }

    private PoolTableRate openRatePeriod(UUID poolTableId, BigDecimal ratePerMinute,
                                         BigDecimal ratePerHour, OffsetDateTime effectiveFrom) {
        PoolTableRate rate = new PoolTableRate();
        rate.setBranchId(branchContext.getCurrentBranchId());
        rate.setPoolTableId(poolTableId);
        rate.setRatePerMinute(RateConversion.perMinuteFrom(ratePerMinute, ratePerHour));
        // Recorded as typed, so the screen can read the admin's own figure back. Null when they
        // typed a per-minute rate, and nothing downstream ever prices from it.
        rate.setRatePerHour(ratePerHour);
        rate.setEffectiveFrom(effectiveFrom);
        rate.setCreatedBy(branchContext.getCurrentUserId());
        return poolTableRateRepository.save(rate);
    }

    private PoolTableRate currentRateOf(UUID poolTableId) {
        return poolTableRateRepository.findCurrentByPoolTableId(poolTableId).orElse(null);
    }

    private PoolTableResponseDTO toResponseDto(PoolTable poolTable, PoolTableRate rate) {
        PoolTableResponseDTO responseDto = poolTableMapper.toResponseDto(poolTable);
        if (rate != null) {
            responseDto.setRatePerMinute(rate.getRatePerMinute());
            responseDto.setRatePerHour(rate.getRatePerHour());
            responseDto.setEffectiveRatePerHour(RateConversion.effectivePerHour(rate.getRatePerMinute()));
        }
        return responseDto;
    }

    private Map<String, Object> rateSnapshot(BigDecimal ratePerMinute, BigDecimal ratePerHour,
                                             OffsetDateTime effectiveFrom) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ratePerMinute", ratePerMinute);
        snapshot.put("ratePerHour", ratePerHour);
        snapshot.put("effectiveFrom", effectiveFrom);
        return snapshot;
    }

    // compareTo, so 240 and 240.00 are the same figure, and null-tolerant because the hourly
    // column is null on every table configured per minute.
    private static boolean sameFigure(BigDecimal stored, BigDecimal requested) {
        if (stored == null || requested == null) {
            return stored == null && requested == null;
        }
        return stored.compareTo(requested) == 0;
    }
}
