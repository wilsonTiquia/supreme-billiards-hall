package com.supremebilliardshall.billiards_hall_system.mapper;

import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseRequestDTO;
import com.supremebilliardshall.billiards_hall_system.entity.Expense;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

// Request side only. The response carries the category name and both usernames, which are
// lookups rather than mappings, so the service builds it — the same split BillServiceImpl uses.
@Mapper(componentModel = "spring")
public interface ExpenseMapper {

    // Everything not on the request is server-owned: who recorded it, when, which night it
    // landed on, and the whole void triple.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "branchId", ignore = true)
    @Mapping(target = "incurredAt", ignore = true)
    @Mapping(target = "businessDate", ignore = true)
    @Mapping(target = "recordedBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "voidedAt", ignore = true)
    @Mapping(target = "voidedBy", ignore = true)
    @Mapping(target = "voidReason", ignore = true)
    Expense toEntity(ExpenseRequestDTO expenseRequestDTO);
}
