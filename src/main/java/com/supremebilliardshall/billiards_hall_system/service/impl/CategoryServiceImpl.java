package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.category.CategoryRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.category.CategoryResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.Category;
import com.supremebilliardshall.billiards_hall_system.exception.DuplicateResourceException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.mapper.CategoryMapper;
import com.supremebilliardshall.billiards_hall_system.repository.CategoryRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.CategoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;
    private final BranchContext branchContext;
    private final AuditService auditService;

    public CategoryServiceImpl(CategoryRepository categoryRepository,
                               CategoryMapper categoryMapper,
                               BranchContext branchContext,
                               AuditService auditService) {
        this.categoryRepository = categoryRepository;
        this.categoryMapper = categoryMapper;
        this.branchContext = branchContext;
        this.auditService = auditService;
    }


    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponseDTO> getAllCategories() {
        return categoryRepository.findAllActive()
                .stream()
                .map(categoryMapper::toResponseDto)
                .toList();
    }

    @Override
    @Transactional
    public CategoryResponseDTO createCategory(CategoryRequestDTO categoryRequestDTO) {
        boolean exists = categoryRepository.existsByName(categoryRequestDTO.getName());
        if (exists) {
            throw new DuplicateResourceException("Category with name '" + categoryRequestDTO.getName() + "' already exists.");
        }

        Category category = categoryMapper.toEntity(categoryRequestDTO);
        category.setBranchId(branchContext.getCurrentBranchId());
        if (category.getSortOrder() == null) {
            category.setSortOrder(0);
        }

        Category savedCategory = categoryRepository.save(category);
        auditService.record("CATEGORY_CREATED", "product_category", savedCategory.getId(),
                null, auditSnapshot(savedCategory), null);
        return categoryMapper.toResponseDto(savedCategory);
    }

    @Override
    @Transactional
    public CategoryResponseDTO updateCategory(UUID id, CategoryRequestDTO categoryRequestDTO) {
        Category existing = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));


        // check if the name is the same
        boolean existingName = categoryRepository.
                existsByNameAndIdNot(categoryRequestDTO.getName(), id);

        if (existingName) {
            throw new DuplicateResourceException("Category with name '" + categoryRequestDTO.getName() + "' already exists.");
        }

        Map<String, Object> before = auditSnapshot(existing);
        Integer sortOrder = existing.getSortOrder();
        categoryMapper.updateEntityFromDto(categoryRequestDTO, existing);
        if (existing.getSortOrder() == null) {
            existing.setSortOrder(sortOrder);
        }

        Category updated = categoryRepository.save(existing);
        auditService.record("CATEGORY_UPDATED", "product_category", updated.getId(),
                before, auditSnapshot(updated), null);
        return categoryMapper.toResponseDto(updated);
    }

    @Override
    @Transactional
    public void deleteCategory(UUID id) {

        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
        Map<String, Object> before = auditSnapshot(category);
        category.setArchivedAt(OffsetDateTime.now());
        Category archived = categoryRepository.save(category);
        auditService.record("CATEGORY_ARCHIVED", "product_category", archived.getId(),
                before, auditSnapshot(archived), null);
    }

    // The same shape every other audited row uses: what a reader needs to see what changed.
    private Map<String, Object> auditSnapshot(Category category) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", category.getName());
        snapshot.put("sortOrder", category.getSortOrder());
        snapshot.put("archivedAt", category.getArchivedAt());
        return snapshot;
    }
}
