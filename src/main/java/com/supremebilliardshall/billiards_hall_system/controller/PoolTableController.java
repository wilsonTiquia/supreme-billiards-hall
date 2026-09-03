package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.FloorViewResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.PoolTableRateRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.PoolTableRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.PoolTableResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.PoolTableService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tables")
public class PoolTableController {

    private final PoolTableService poolTableService;

    public PoolTableController(PoolTableService poolTableService) {
        this.poolTableService = poolTableService;
    }

    // The floor view: each table with its current session summary, plus serverNow.
    @GetMapping
    public ResponseEntity<APIResponse<FloorViewResponseDTO>> getAll() {
        FloorViewResponseDTO floorView = poolTableService.getFloorView();
        return ResponseEntity.
                ok(APIResponse.success(
                        floorView,
                        "Floor view fetched successfully"));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<PoolTableResponseDTO>> createTable(@Valid @RequestBody PoolTableRequestDTO poolTableRequestDTO) {
        PoolTableResponseDTO savedTable = poolTableService.createTable(poolTableRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        savedTable,
                        "Table created successfully"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<PoolTableResponseDTO>> updateTable(@PathVariable UUID id,
                                                                        @Valid @RequestBody PoolTableRequestDTO poolTableRequestDTO) {
        PoolTableResponseDTO updatedTable = poolTableService.updateTable(id, poolTableRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        updatedTable,
                        "Table updated successfully"));
    }

    @PutMapping("/{id}/rate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<PoolTableResponseDTO>> changeRate(@PathVariable UUID id,
                                                                       @Valid @RequestBody PoolTableRateRequestDTO poolTableRateRequestDTO) {
        PoolTableResponseDTO updatedTable = poolTableService.changeRate(id, poolTableRateRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        updatedTable,
                        "Table rate updated successfully"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<PoolTableResponseDTO>> deleteTable(@PathVariable UUID id) {
        poolTableService.deleteTable(id);
        return ResponseEntity.ok(
                APIResponse.success(null, "Table archived successfully with id: " + id)
        );
    }

}
