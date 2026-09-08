package com.supremebilliardshall.billiards_hall_system.mapper;

import com.supremebilliardshall.billiards_hall_system.dto.pooltable.PoolTableRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.PoolTableResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.PoolTable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface PoolTableMapper {

    // Every rate figure lives on pool_table_rate and session on table_session, so the service
    // sets them all after mapping.
    @Mapping(target = "ratePerMinute", ignore = true)
    @Mapping(target = "ratePerHour", ignore = true)
    @Mapping(target = "effectiveRatePerHour", ignore = true)
    @Mapping(target = "session", ignore = true)
    PoolTableResponseDTO toResponseDto(PoolTable poolTable);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "branchId", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    PoolTable toEntity(PoolTableRequestDTO poolTableRequestDTO);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "branchId", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntityFromDto(PoolTableRequestDTO dto, @MappingTarget PoolTable entity);

}
