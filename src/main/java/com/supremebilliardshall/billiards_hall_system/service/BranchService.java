package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.branch.BranchRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.branch.BranchResponseDTO;

import java.util.List;
import java.util.UUID;

public interface BranchService {
    List<BranchResponseDTO> getAllBranches();

    List<BranchResponseDTO> searchBranchesByName(String name);

    BranchResponseDTO createBranch(BranchRequestDTO branchRequestDTO);

    // Update Branch
    BranchResponseDTO updateBranch(UUID id, BranchRequestDTO branchRequestDTO);

    // Deactivate Branch — branch has no archived_at, and its rows must never orphan
    void deleteBranch(UUID id);
}
