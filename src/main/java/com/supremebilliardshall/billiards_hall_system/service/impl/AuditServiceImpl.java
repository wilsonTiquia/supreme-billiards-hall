package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.PagedResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.audit.AuditFeedEntryDTO;
import com.supremebilliardshall.billiards_hall_system.dto.audit.AuditFilterOptionsDTO;
import com.supremebilliardshall.billiards_hall_system.dto.audit.AuditLogResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.AuditLog;
import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.AuditLogRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditServiceImpl implements AuditService {

    // A page of audit rows should be readable on a screen, not downloaded.
    private static final int MAX_PAGE_SIZE = 200;

    private final AuditLogRepository auditLogRepository;
    private final AppUserRepository appUserRepository;
    private final BranchContext branchContext;

    public AuditServiceImpl(AuditLogRepository auditLogRepository,
                            AppUserRepository appUserRepository,
                            BranchContext branchContext) {
        this.auditLogRepository = auditLogRepository;
        this.appUserRepository = appUserRepository;
        this.branchContext = branchContext;
    }

    @Override
    // MANDATORY: an audit row that commits separately from the change it records is worse
    // than none at all. This must join the caller's transaction, never start its own.
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityTable, UUID entityId,
                       Map<String, Object> before, Map<String, Object> after, String note) {

        AuditLog auditLog = new AuditLog();
        auditLog.setBranchId(branchContext.getCurrentBranchId());
        auditLog.setActorId(branchContext.getCurrentUserId());
        auditLog.setAction(action);
        auditLog.setEntityTable(entityTable);
        auditLog.setEntityId(entityId);
        auditLog.setBefore(before);
        auditLog.setAfter(after);
        auditLog.setNote(note);

        auditLogRepository.save(auditLog);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponseDTO<AuditLogResponseDTO> search(String entityTable, UUID actorId,
                                                        LocalDate from, LocalDate to, int page, int size) {
        // Unbounded dates rather than null ones: the range is always applied, so Postgres
        // never has to infer a type for a null date.
        LocalDate fromDate = from != null ? from : LocalDate.of(1900, 1, 1);
        LocalDate toDate = to != null ? to : LocalDate.of(9999, 12, 31);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Page<AuditLog> results = auditLogRepository.search(
                entityTable, actorId, fromDate, toDate, PageRequest.of(Math.max(page, 0), pageSize));

        // One lookup for the actors on this page, rather than one per row.
        Map<UUID, String> usernames = new HashMap<>();
        List<UUID> actorIds = results.getContent().stream()
                .map(AuditLog::getActorId).filter(java.util.Objects::nonNull).distinct().toList();
        if (!actorIds.isEmpty()) {
            for (AppUser user : appUserRepository.findAllById(actorIds)) {
                usernames.put(user.getId(), user.getUsername());
            }
        }

        List<AuditLogResponseDTO> content = results.getContent().stream()
                .map(entry -> new AuditLogResponseDTO(
                        entry.getId(), entry.getAction(), entry.getEntityTable(), entry.getEntityId(),
                        entry.getActorId(), usernames.get(entry.getActorId()),
                        entry.getBefore(), entry.getAfter(), entry.getNote(),
                        entry.getOccurredAt(), entry.getBusinessDate()))
                .toList();

        return new PagedResponseDTO<>(content, results.getNumber(), results.getSize(),
                results.getTotalElements(), results.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponseDTO<AuditFeedEntryDTO> feed(String action, UUID actorId, String entityTable,
                                                    LocalDate from, LocalDate to, int page, int size) {
        // Bounded rather than null, as in search(): the range is always applied so Postgres
        // never has to infer a type for a null date.
        LocalDate fromDate = from != null ? from : LocalDate.of(1900, 1, 1);
        LocalDate toDate = to != null ? to : LocalDate.of(9999, 12, 31);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        UUID branchId = branchContext.getCurrentBranchId();

        List<AuditLogRepository.AuditFeedProjection> rows = auditLogRepository.searchFeed(
                branchId, action, actorId, entityTable, fromDate, toDate,
                pageNumber * pageSize, pageSize);
        long total = auditLogRepository.countMatching(
                branchId, action, actorId, entityTable, fromDate, toDate);

        // One lookup for the actors on this page rather than one per row, as elsewhere.
        Map<UUID, String> names = new HashMap<>();
        List<UUID> actorIds = rows.stream()
                .map(AuditLogRepository.AuditFeedProjection::getActorId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!actorIds.isEmpty()) {
            for (AppUser user : appUserRepository.findAllById(actorIds)) {
                names.put(user.getId(), displayName(user));
            }
        }

        /*
         * before/after come from the entities, not from the union.
         *
         * The union has to cast jsonb to text to travel through a native projection, and turning
         * that text back into a Map would mean parsing JSON here — for columns Hibernate already
         * maps. So the AUDIT rows on this page are re-read by id in one go and their diffs taken
         * from the mapping that already exists. Stock rows have no diff: the movement itself is
         * the change, and quantityDelta carries it.
         */
        Map<UUID, AuditLog> diffs = new HashMap<>();
        List<UUID> auditIds = rows.stream()
                .filter(row -> "AUDIT".equals(row.getSource()))
                .map(AuditLogRepository.AuditFeedProjection::getId).toList();
        if (!auditIds.isEmpty()) {
            for (AuditLog entry : auditLogRepository.findAllById(auditIds)) {
                diffs.put(entry.getId(), entry);
            }
        }

        List<AuditFeedEntryDTO> content = new ArrayList<>(rows.size());
        for (AuditLogRepository.AuditFeedProjection row : rows) {
            content.add(new AuditFeedEntryDTO(
                    row.getId(),
                    row.getSource(),
                    row.getAction(),
                    actionLabel(row),
                    AuditVocabulary.entity(row.getEntityTable()),
                    row.getEntityLabel(),
                    names.get(row.getActorId()),
                    row.getNote(),
                    row.getQuantityDelta(),
                    row.getCustomerTypeName(),
                    diffs.containsKey(row.getId()) ? diffs.get(row.getId()).getBefore() : null,
                    diffs.containsKey(row.getId()) ? diffs.get(row.getId()).getAfter() : null,
                    // Native projections hand back Instant for timestamptz.
                    row.getOccurredAt() == null ? null : row.getOccurredAt().atOffset(ZoneOffset.UTC),
                    row.getBusinessDate()));
        }

        int totalPages = (int) Math.ceil((double) total / pageSize);
        return new PagedResponseDTO<>(content, pageNumber, pageSize, total, totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditFilterOptionsDTO filterOptions() {
        UUID branchId = branchContext.getCurrentBranchId();

        List<AuditFilterOptionsDTO.ActionOption> actions =
                auditLogRepository.findDistinctActions(branchId).stream()
                        .map(a -> new AuditFilterOptionsDTO.ActionOption(a, AuditVocabulary.action(a)))
                        // Sorted by what the owner reads, not by the constant behind it.
                        .sorted(java.util.Comparator.comparing(AuditFilterOptionsDTO.ActionOption::getLabel))
                        .toList();

        List<AuditFilterOptionsDTO.ActorOption> actors =
                auditLogRepository.findActors(branchId).stream()
                        .map(a -> new AuditFilterOptionsDTO.ActorOption(
                                a.getId(),
                                a.getFullName() != null && !a.getFullName().isBlank()
                                        ? a.getFullName() : a.getUsername()))
                        .toList();

        return new AuditFilterOptionsDTO(actions, actors);
    }

    /*
     * A rate override is named after the customer type it was given on: "Happy Hour rate".
     *
     * The vocabulary cannot do this on its own -- it maps a constant to a fixed phrase, and the
     * owner now has customer types beyond friends, so a single phrase misnames all but one of
     * them. The type comes from the feed's join on table_session.
     *
     * Gated on the action. SESSION_TIME_REDUCED also happens on a session with a customer type
     * and must not read "Happy Hour rate"; it is not a rate change at all.
     *
     * Falls back to the vocabulary when the type is absent. That is a real path, not merely a
     * defensive one: table_session.customer_type_id is nullable.
     */
    private String actionLabel(AuditLogRepository.AuditFeedProjection row) {
        String customerType = row.getCustomerTypeName();
        if ("SESSION_RATE_OVERRIDE".equals(row.getAction())
                && customerType != null && !customerType.isBlank()) {
            return customerType.trim() + " rate";
        }
        return AuditVocabulary.action(row.getAction());
    }

    // The name people call each other by, falling back to the login when it is missing.
    private String displayName(AppUser user) {
        return user.getFullName() != null && !user.getFullName().isBlank()
                ? user.getFullName() : user.getUsername();
    }
}
