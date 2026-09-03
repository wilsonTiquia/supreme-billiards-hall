package com.supremebilliardshall.billiards_hall_system.mapper;

import com.supremebilliardshall.billiards_hall_system.dto.product.ProductAdminResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.product.ProductRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.product.ProductResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface ProductMapper {
    ProductResponseDTO toResponseDto(Product product);

    // Separate target type rather than a nullable field: an employee response must not
    // contain the key at all.
    ProductAdminResponseDTO toAdminResponseDto(Product product);

    // avg_cost and qty_on_hand are owned by the stock ledger, not by this request.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "branchId", ignore = true)
    @Mapping(target = "avgCost", ignore = true)
    @Mapping(target = "qtyOnHand", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    // The image is owned by the upload route, never by a product edit.
    @Mapping(target = "imagePath", ignore = true)
    @Mapping(target = "imageSha256", ignore = true)
    @Mapping(target = "imageBytes", ignore = true)
    Product toEntity(ProductRequestDTO productRequestDTO);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "branchId", ignore = true)
    @Mapping(target = "avgCost", ignore = true)
    @Mapping(target = "qtyOnHand", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "imagePath", ignore = true)
    @Mapping(target = "imageSha256", ignore = true)
    @Mapping(target = "imageBytes", ignore = true)
    void updateEntityFromDto(ProductRequestDTO dto, @MappingTarget Product entity);

}
