package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.category.CategoryRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.category.CategoryResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public ResponseEntity<APIResponse<List<CategoryResponseDTO>>> getAll() {
        List<CategoryResponseDTO> categoriesDto = categoryService.getAllCategories();
        return ResponseEntity.
                ok(APIResponse.success(
                        categoriesDto,
                        "All Categories fetched successfully"));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<CategoryResponseDTO>> createCategory(@Valid @RequestBody CategoryRequestDTO categoryRequestDTO) {
        CategoryResponseDTO savedCategory = categoryService.createCategory(categoryRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        savedCategory,
                        "Category created successfully"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<CategoryResponseDTO>> updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryRequestDTO categoryRequestDTO) {
        CategoryResponseDTO updatedCategory = categoryService.updateCategory(id,categoryRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        updatedCategory,
                        "Category updated successfully"));
    }


    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<CategoryResponseDTO>> deleteCategory(@PathVariable UUID id) {
       categoryService.deleteCategory(id);
        return ResponseEntity.ok(
                APIResponse.success(null, "Category archived successfully with id: " + id)
        );
    }

}
