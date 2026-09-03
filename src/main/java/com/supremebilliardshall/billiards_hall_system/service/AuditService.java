package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.PagedResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.audit.AuditFeedEntryDTO;
import com.supremebilliardshall.billiards_hall_system.dto.audit.AuditFilterOptionsDTO;
import com.supremebilliardshall.billiards_hall_system.dto.audit.AuditLogResponseDTO;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public interface AuditService {
    // Writes one audit_log row for the acting user, in the caller's transaction.
    void record(String action, String entityTable, UUID entityId,
                Map<String, Object> before, Map<String, Object> after, String note);

    // Reads the log back. Every filter optional; newest first.
    PagedResponseDTO<AuditLogResponseDTO> search(String entityTable, UUID actorId,
                                                 LocalDate from, LocalDate to, int page, int size);

    // The same history with the stock ledger folded in and every id resolved to a name. This is
    // what the Audit screen reads; search() remains the raw view of audit_log alone.
    PagedResponseDTO<AuditFeedEntryDTO> feed(String action, UUID actorId, String entityTable,
                                             LocalDate from, LocalDate to, int page, int size);

    // What the filters can offer, drawn from what is actually in this branch's history.
    AuditFilterOptionsDTO filterOptions();
}
