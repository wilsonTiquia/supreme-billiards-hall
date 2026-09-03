package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.pooltable.FloorViewResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.PoolTableRateRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.PoolTableRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.PoolTableResponseDTO;

import java.util.List;
import java.util.UUID;

public interface PoolTableService {
    // The floor view: every table with its live session summary, plus the server clock.
    FloorViewResponseDTO getFloorView();

    PoolTableResponseDTO createTable(PoolTableRequestDTO poolTableRequestDTO);

    // Update Table
    PoolTableResponseDTO updateTable(UUID id, PoolTableRequestDTO poolTableRequestDTO);

    // Closes the current rate period and opens a new one. Never mutates the existing row.
    PoolTableResponseDTO changeRate(UUID id, PoolTableRateRequestDTO poolTableRateRequestDTO);

    // Archive Table — rejected while a session is open on it
    void deleteTable(UUID id);
}
