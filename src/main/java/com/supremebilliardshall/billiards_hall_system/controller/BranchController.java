package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.branch.BranchRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.branch.BranchResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.BranchService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/branches")
@PreAuthorize("hasRole('ADMIN')")
public class BranchController {

    private final BranchService branchService;

    public BranchController(BranchService branchService) {
        this.branchService = branchService;
    }

    // Get All Branches
    @GetMapping
    public ResponseEntity<APIResponse<List<BranchResponseDTO>>> getAll() {
        List<BranchResponseDTO> branchResponseDTOs = branchService.getAllBranches();
        // Wrap the list in APIResponse
        return ResponseEntity.ok(APIResponse.success(branchResponseDTOs, "All branches fetched successfully"));
    }

    @GetMapping("/search")
    public ResponseEntity<APIResponse<List<BranchResponseDTO>>> searchByName(@RequestParam String name){

        List<BranchResponseDTO> searchedList = branchService.searchBranchesByName(name);

        return ResponseEntity.ok(APIResponse.success(searchedList, "Filtered by " + name));
    }

    @PostMapping
    public ResponseEntity<APIResponse<BranchResponseDTO>> createBranch(@Valid @RequestBody BranchRequestDTO branchRequestDTO) {
        BranchResponseDTO createdBranch = branchService.createBranch(branchRequestDTO);

        return ResponseEntity.ok(APIResponse.success(createdBranch, "Branch created successfully"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<APIResponse<BranchResponseDTO>> updateBranch(@PathVariable UUID id,
                                                                       @Valid @RequestBody BranchRequestDTO branchRequestDTO) {
        BranchResponseDTO updatedBranch = branchService.updateBranch(id, branchRequestDTO);

        return ResponseEntity.ok(
                APIResponse.success(updatedBranch, "Branch updated successfully with id: " + id)
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<APIResponse<BranchResponseDTO>> deleteBranch(@PathVariable UUID id) {

        branchService.deleteBranch(id);
        return ResponseEntity.ok(
                APIResponse.success(null, "Branch deactivated successfully with id: " + id)
        );
    }
}
