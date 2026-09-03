package com.supremebilliardshall.billiards_hall_system.mapper;

import com.supremebilliardshall.billiards_hall_system.dto.customertype.CustomerTypeRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.customertype.CustomerTypeResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.CustomerType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface CustomerTypeMapper {
    CustomerTypeResponseDTO toResponseDto(CustomerType customerType);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "branchId", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    CustomerType toEntity(CustomerTypeRequestDTO customerTypeRequestDTO);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "branchId", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntityFromDto(CustomerTypeRequestDTO dto, @MappingTarget CustomerType entity);

}
