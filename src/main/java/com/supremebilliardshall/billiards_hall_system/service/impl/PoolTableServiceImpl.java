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
        Map<UUID, BigDecimal> currentRates = poolTableRateRepository.findAllCurrent()
                .stream()
                .collect(Collectors.toMap(PoolTableRate::getPoolTableId, PoolTableRate::getRatePerMinute));
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
        PoolTableRate rate = openRatePeriod(savedTable.getId(),
                poolTableRequestDTO.getRatePerMinute(), OffsetDateTime.now());

        auditService.record("POOL_TABLE_CREATED", "pool_table", savedTable.getId(),
                null, auditSnapshot(savedTable, rate.getRatePerMinute()), null);

        return toResponseDto(savedTable, rate.getRatePerMinute());
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
        BigDecimal currentRate = currentRateOf(id);
        BigDecimal requestedRate = poolTableRequestDTO.getRatePerMinute();
        if (currentRate == null || currentRate.compareTo(requestedRate) != 0) {
            // changeRate writes its own POOL_TABLE_RATE_CHANGED row; this one records the rest
            // of the edit, so a rename that also repriced leaves both facts on the log.
            auditService.record("POOL_TABLE_UPDATED", "pool_table", updated.getId(),
                    before, auditSnapshot(updated, requestedRate), null);
            return changeRate(id, new PoolTableRateRequestDTO(requestedRate, null));
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

        BigDecimal previousRate = null;
        PoolTableRate current = poolTableRateRepository.findCurrentByPoolTableId(id).orElse(null);
        if (current != null) {
            previousRate = current.getRatePerMinute();
            current.setEffectiveTo(effectiveFrom);
            // Flushed before the insert: Hibernate orders inserts ahead of updates, and the
            // pool_table_rate_no_overlap exclusion constraint is not deferrable.
            poolTableRateRepository.saveAndFlush(current);
        }

        PoolTableRate rate = openRatePeriod(id, poolTableRateRequestDTO.getRatePerMinute(), effectiveFrom);

        auditService.record("POOL_TABLE_RATE_CHANGED", "pool_table_rate", rate.getId(),
                rateSnapshot(previousRate, null),
                rateSnapshot(rate.getRatePerMinute(), effectiveFrom),
                "Rate change for table " + poolTable.getName());

        return toResponseDto(poolTable, rate.getRatePerMinute());
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

    private Map<String, Object> auditSnapshot(PoolTable table, BigDecimal ratePerMinute) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", table.getName());
        snapshot.put("tableNumber", table.getTableNumber());
        snapshot.put("ratePerMinute", ratePerMinute);
        snapshot.put("isActive", table.getIsActive());
        snapshot.put("archivedAt", table.getArchivedAt());
        return snapshot;
    }

    private PoolTableRate openRatePeriod(UUID poolTableId, BigDecimal ratePerMinute, OffsetDateTime effectiveFrom) {
        PoolTableRate rate = new PoolTableRate();
        rate.setBranchId(branchContext.getCurrentBranchId());
        rate.setPoolTableId(poolTableId);
        rate.setRatePerMinute(ratePerMinute);
        rate.setEffectiveFrom(effectiveFrom);
        rate.setCreatedBy(branchContext.getCurrentUserId());
        return poolTableRateRepository.save(rate);
    }

    private BigDecimal currentRateOf(UUID poolTableId) {
        return poolTableRateRepository.findCurrentByPoolTableId(poolTableId)
                .map(PoolTableRate::getRatePerMinute)
                .orElse(null);
    }

    private PoolTableResponseDTO toResponseDto(PoolTable poolTable, BigDecimal ratePerMinute) {
        PoolTableResponseDTO responseDto = poolTableMapper.toResponseDto(poolTable);
        responseDto.setRatePerMinute(ratePerMinute);
        return responseDto;
    }

    private Map<String, Object> rateSnapshot(BigDecimal ratePerMinute, OffsetDateTime effectiveFrom) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ratePerMinute", ratePerMinute);
        snapshot.put("effectiveFrom", effectiveFrom);
        return snapshot;
    }
}
