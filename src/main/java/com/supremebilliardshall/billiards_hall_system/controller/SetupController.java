package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.setup.SetupItemResponseDTO;
import com.supremebilliardshall.billiards_hall_system.security.SessionInvalidator;
import com.supremebilliardshall.billiards_hall_system.service.SetupKind;
import com.supremebilliardshall.billiards_hall_system.service.SetupService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/setup/{kind}")
@PreAuthorize("hasRole('ADMIN')")
public class SetupController {
    private final SetupService service;
    private final SessionInvalidator sessions;

    public SetupController(SetupService service, SessionInvalidator sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping
    public ResponseEntity<APIResponse<List<SetupItemResponseDTO>>> list(@PathVariable String kind) {
        return ResponseEntity.ok(APIResponse.success(service.list(SetupKind.fromPath(kind)), "Setup items fetched"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<APIResponse<Void>> delete(@PathVariable String kind, @PathVariable UUID id) {
        SetupKind type = SetupKind.fromPath(kind);
        service.delete(type, id);
        if (type == SetupKind.STAFF) sessions.invalidateAllSessions(id);
        return ResponseEntity.ok(APIResponse.success(null, "Item deleted"));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<APIResponse<Void>> archive(@PathVariable String kind, @PathVariable UUID id) {
        SetupKind type = SetupKind.fromPath(kind);
        service.archive(type, id);
        if (type == SetupKind.STAFF) sessions.invalidateAllSessions(id);
        return ResponseEntity.ok(APIResponse.success(null, "Item archived"));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<APIResponse<Void>> restore(@PathVariable String kind, @PathVariable UUID id) {
        service.restore(SetupKind.fromPath(kind), id);
        return ResponseEntity.ok(APIResponse.success(null, "Item restored"));
    }
}
