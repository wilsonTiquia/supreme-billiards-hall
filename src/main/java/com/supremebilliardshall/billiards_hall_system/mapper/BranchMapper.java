package com.supremebilliardshall.billiards_hall_system.mapper;

import com.supremebilliardshall.billiards_hall_system.dto.branch.BranchRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.branch.BranchResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface BranchMapper {

    // Map Request DTO -> Entity
    // next_receipt_no is allocated at checkout under row lock, never from a request.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "nextReceiptNo", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Branch toEntity(BranchRequestDTO branchRequestDTO);

     // Map Entity → Response DTO
    BranchResponseDTO toResponseDto(Branch branch);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "nextReceiptNo", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntityFromDto(BranchRequestDTO dto, @MappingTarget Branch entity);



}
