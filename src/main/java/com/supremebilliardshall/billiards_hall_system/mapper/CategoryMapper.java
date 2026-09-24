package com.supremebilliardshall.billiards_hall_system.mapper;

import com.supremebilliardshall.billiards_hall_system.dto.category.CategoryRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.category.CategoryResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.Category;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface CategoryMapper {
    CategoryResponseDTO toResponseDto(Category category);

    // branch_id and the timestamps are server-owned; the service sets the branch from the
    // authenticated user, never from the request body.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "branchId", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "referencedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Category toEntity(CategoryRequestDTO categoryRequestDTO);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "branchId", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "referencedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntityFromDto(CategoryRequestDTO dto, @MappingTarget Category entity);

}
