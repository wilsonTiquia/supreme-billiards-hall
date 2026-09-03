package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.branch.BranchRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.branch.BranchResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import com.supremebilliardshall.billiards_hall_system.exception.DuplicateResourceException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.mapper.BranchMapper;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.service.BranchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BranchServiceImpl implements BranchService {

    private final BranchRepository branchRepository;
    private final BranchMapper branchMapper;

    public BranchServiceImpl(BranchRepository branchRepository, BranchMapper branchMapper) {
        this.branchRepository = branchRepository;
        this.branchMapper = branchMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchResponseDTO> getAllBranches() {
        return branchRepository.findAll()
                .stream()
                .map(branchMapper::toResponseDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchResponseDTO> searchBranchesByName(String name) {
        return branchRepository.findByNameContainingIgnoreCase(name)
                .stream()
                .map(branchMapper::toResponseDto)
                .toList();
    }

    @Override
    @Transactional
    public BranchResponseDTO createBranch(BranchRequestDTO branchRequestDTO) {
        boolean exists = branchRepository.existsByCode(branchRequestDTO.getCode());
        if (exists) {
            throw new DuplicateResourceException("Branch with code '" + branchRequestDTO.getCode() + "' already exists.");
        }

        Branch branch = branchMapper.toEntity(branchRequestDTO);
        branch.setNextReceiptNo(1L);
        if (branch.getIsActive() == null) {
            branch.setIsActive(true);
        }

        // Flushed, not just saved: with application-assigned UUIDs Hibernate has no reason to
        // hit the database until commit, so @CreationTimestamp has not fired yet and the
        // response would carry a null createdAt for a row that has one.
        Branch savedBranch = branchRepository.saveAndFlush(branch);
        return branchMapper.toResponseDto(savedBranch);
    }

    @Override
    @Transactional
    public BranchResponseDTO updateBranch(UUID id, BranchRequestDTO branchRequestDTO) {
        Branch existing = branchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", id));


        // check if the code is the same
        boolean existingCode = branchRepository.
                existsByCodeAndIdNot(branchRequestDTO.getCode(), id);

        if (existingCode) {
            throw new DuplicateResourceException("Branch with code '" + branchRequestDTO.getCode() + "' already exists.");
        }

        Boolean isActive = existing.getIsActive();
        branchMapper.updateEntityFromDto(branchRequestDTO, existing);
        if (existing.getIsActive() == null) {
            existing.setIsActive(isActive);
        }

        Branch updated = branchRepository.save(existing);
        return branchMapper.toResponseDto(updated);
    }

    @Override
    @Transactional
    public void deleteBranch(UUID id) {
        Branch branch = branchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", id));
        branch.setIsActive(false);
        branchRepository.save(branch);
    }
}
