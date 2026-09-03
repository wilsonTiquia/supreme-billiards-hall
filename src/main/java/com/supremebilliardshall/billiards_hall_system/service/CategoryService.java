package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.category.CategoryRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.category.CategoryResponseDTO;

import java.util.List;
import java.util.UUID;

public interface CategoryService {
    List<CategoryResponseDTO> getAllCategories();

    CategoryResponseDTO createCategory(CategoryRequestDTO categoryRequestDTO);

    // Update Category
    CategoryResponseDTO updateCategory(UUID id, CategoryRequestDTO categoryRequestDTO);

    // Archive Category — never a hard delete, historical bill lines reference it
    void deleteCategory(UUID id);
}
