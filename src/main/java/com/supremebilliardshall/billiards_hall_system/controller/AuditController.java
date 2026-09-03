package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.PagedResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.audit.AuditFeedEntryDTO;
import com.supremebilliardshall.billiards_hall_system.dto.audit.AuditFilterOptionsDTO;
import com.supremebilliardshall.billiards_hall_system.dto.audit.AuditLogResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

// Who voided that beer, who set a 0.50/min friend rate, who corrected stock on the 12th.
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<APIResponse<PagedResponseDTO<AuditLogResponseDTO>>> search(
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        PagedResponseDTO<AuditLogResponseDTO> results =
                auditService.search(entity, actor, from, to, page, size);
        return ResponseEntity.
                ok(APIResponse.success(
                        results,
                        "Audit log fetched successfully"));
    }

    // What the Audit screen reads: the same history with the stock ledger folded in, every id
    // already resolved to a name, and every action already in words.
    @GetMapping("/feed")
    public ResponseEntity<APIResponse<PagedResponseDTO<AuditFeedEntryDTO>>> feed(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        PagedResponseDTO<AuditFeedEntryDTO> results =
                auditService.feed(action, actor, entity, from, to, page, size);
        return ResponseEntity.
                ok(APIResponse.success(
                        results,
                        "Audit feed fetched successfully"));
    }

    @GetMapping("/filters")
    public ResponseEntity<APIResponse<AuditFilterOptionsDTO>> filters() {
        AuditFilterOptionsDTO options = auditService.filterOptions();
        return ResponseEntity.
                ok(APIResponse.success(
                        options,
                        "Audit filters fetched successfully"));
    }

}
