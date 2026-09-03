package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.settings.SettingsRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.settings.SettingsResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.SettingsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings")
@PreAuthorize("hasRole('ADMIN')")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ResponseEntity<APIResponse<SettingsResponseDTO>> getSettings() {
        SettingsResponseDTO settings = settingsService.getSettings();
        return ResponseEntity.
                ok(APIResponse.success(
                        settings,
                        "Settings fetched successfully"));
    }

    @PutMapping
    public ResponseEntity<APIResponse<SettingsResponseDTO>> updateSettings(@Valid @RequestBody SettingsRequestDTO settingsRequestDTO) {
        SettingsResponseDTO updated = settingsService.updateSettings(settingsRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        updated,
                        "Settings updated successfully"));
    }
}
