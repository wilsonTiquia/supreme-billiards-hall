package com.supremebilliardshall.billiards_hall_system.mapper;

import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseCategoryRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseCategoryResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.ExpenseCategory;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface ExpenseCategoryMapper {
    ExpenseCategoryResponseDTO toResponseDto(ExpenseCategory expenseCategory);

    // branch_id and the timestamps are server-owned; the service sets the branch from the
    // authenticated user, never from the request body.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "branchId", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    ExpenseCategory toEntity(ExpenseCategoryRequestDTO expenseCategoryRequestDTO);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "branchId", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntityFromDto(ExpenseCategoryRequestDTO dto, @MappingTarget ExpenseCategory entity);

}
