package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.setup.SetupItemResponseDTO;
import java.util.List;
import java.util.UUID;

public interface SetupService {
    List<SetupItemResponseDTO> list(SetupKind kind);
    void delete(SetupKind kind, UUID id);
    void archive(SetupKind kind, UUID id);
    void restore(SetupKind kind, UUID id);
}
