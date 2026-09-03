package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.product.ProductRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.product.ProductResponseDTO;

import java.util.List;
import java.util.UUID;

public interface ProductService {
    // Returns ProductAdminResponseDTO instances for an admin and plain ones for an employee.
    // includeArchived is honoured for an ADMIN and ignored for anyone else.
    List<ProductResponseDTO> searchProducts(UUID categoryId, String q, boolean activeOnly,
                                            boolean includeArchived);

    ProductResponseDTO createProduct(ProductRequestDTO productRequestDTO);

    // UPDATE
    ProductResponseDTO updateProduct(UUID id, ProductRequestDTO productRequestDTO);

    // Archive Product — never a hard delete, historical bill lines reference it
    void deleteProduct(UUID id);

    // Puts an archived product back on the counter. Refused when a live product has since
    // taken its name, because product_name_key only covers unarchived rows.
    ProductResponseDTO unarchiveProduct(UUID id);

}
