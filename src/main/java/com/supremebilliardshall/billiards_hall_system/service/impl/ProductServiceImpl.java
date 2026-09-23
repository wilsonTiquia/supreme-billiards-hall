package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.product.ProductRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.stock.StockDeliveryRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.stock.StockDeliveryLineRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.product.ProductResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.Category;
import com.supremebilliardshall.billiards_hall_system.entity.Product;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.exception.DuplicateResourceException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.mapper.ProductMapper;
import com.supremebilliardshall.billiards_hall_system.repository.CategoryRepository;
import com.supremebilliardshall.billiards_hall_system.repository.ProductRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.ProductService;
import com.supremebilliardshall.billiards_hall_system.service.StockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductMapper productMapper;
    private final BranchContext branchContext;
    private final AuditService auditService;
    private final StockService stockService;

    public ProductServiceImpl(ProductRepository productRepository,
                              CategoryRepository categoryRepository,
                              ProductMapper productMapper,
                              BranchContext branchContext,
                              AuditService auditService,
                              StockService stockService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productMapper = productMapper;
        this.branchContext = branchContext;
        this.auditService = auditService;
        this.stockService = stockService;
    }


    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> searchProducts(UUID categoryId, String q, boolean activeOnly,
                                                   boolean includeArchived) {
        // Enforced here rather than in the controller: an employee asking for archived rows
        // would be asking to sell one, and the counter must never be offered that.
        return productRepository.search(categoryId, q, activeOnly, includeArchived && branchContext.isAdmin())
                .stream()
                .map(this::toResponseDto)
                .toList();
    }

    @Override
    @Transactional
    public ProductResponseDTO createProduct(ProductRequestDTO productRequestDTO) {
        boolean exists = productRepository.existsByName(productRequestDTO.getName());
        if (exists) {
            throw new DuplicateResourceException("Product with name '" + productRequestDTO.getName() + "' already exists.");
        }

        requireCategoryInBranch(productRequestDTO.getCategoryId());

        Product product = productMapper.toEntity(productRequestDTO);
        product.setBranchId(branchContext.getCurrentBranchId());
        // Stock and cost start at zero and only ever move through the stock ledger.
        product.setAvgCost(BigDecimal.ZERO);
        product.setQtyOnHand(BigDecimal.ZERO);
        if (product.getIsActive() == null) {
            product.setIsActive(true);
        }

        Product savedProduct = productRepository.save(product);
        // A log that records edits but not creations implies a completeness it does not have.
        auditService.record("PRODUCT_CREATED", "product", savedProduct.getId(),
                null, auditSnapshot(savedProduct), null);
        if (productRequestDTO.getOpeningStock() != null) {
            var opening = productRequestDTO.getOpeningStock();
            // Joins this transaction: a failed delivery rolls back the product and its audit too.
            stockService.receiveDelivery(new StockDeliveryRequestDTO(null, null, "Opening stock",
                    List.of(new StockDeliveryLineRequestDTO(savedProduct.getId(),
                            opening.getQuantity(), opening.getUnitCost()))));
        }
        return toResponseDto(savedProduct);
    }

    @Override
    @Transactional
    public ProductResponseDTO updateProduct(UUID id, ProductRequestDTO productRequestDTO) {
        if (productRequestDTO.getOpeningStock() != null) {
            throw new BusinessRuleException("Opening stock is only for new products. Use Add stock to receive a delivery.");
        }
        Product existing = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        // check if the name is the same
        boolean existingName = productRepository.
                existsByNameAndIdNot(productRequestDTO.getName(), id);

        if (existingName) {
            throw new DuplicateResourceException("Product with name '" + productRequestDTO.getName() + "' already exists.");
        }

        requireCategoryInBranch(productRequestDTO.getCategoryId());

        // Snapshot before mutating: a price edit changes what a customer is charged from
        // now on, so it has to be attributable.
        Map<String, Object> before = auditSnapshot(existing);

        Boolean isActive = existing.getIsActive();
        productMapper.updateEntityFromDto(productRequestDTO, existing);
        if (existing.getIsActive() == null) {
            existing.setIsActive(isActive);
        }

        Product updated = productRepository.save(existing);
        auditService.record("PRODUCT_UPDATED", "product", updated.getId(),
                before, auditSnapshot(updated), null);

        return toResponseDto(updated);
    }

    @Override
    @Transactional
    public void deleteProduct(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
        // The image file is left where it is on purpose, so a restore brings the picture back
        // with the product.
        Map<String, Object> before = auditSnapshot(product);
        product.setArchivedAt(OffsetDateTime.now());
        Product archived = productRepository.save(product);

        // Taking something off the menu is a bigger change than editing its price, and that
        // already writes a row. "Who took that off the list, and when" has to be answerable.
        auditService.record("PRODUCT_ARCHIVED", "product", archived.getId(),
                before, auditSnapshot(archived), null);
    }

    @Override
    @Transactional
    public ProductResponseDTO unarchiveProduct(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        if (product.getArchivedAt() == null) {
            throw new BusinessRuleException(
                    "Product '" + product.getName() + "' is not archived.");
        }

        // product_name_key covers unarchived rows only, so the name became reusable the moment
        // this was archived. If something took it, restoring would hit the index — said here
        // as an instruction the owner can act on rather than as a constraint violation.
        if (productRepository.existsByName(product.getName())) {
            throw new DuplicateResourceException(
                    "Cannot restore '" + product.getName() + "': another product is using that "
                            + "name now. Rename that one first, then restore this.");
        }

        Map<String, Object> before = auditSnapshot(product);
        product.setArchivedAt(null);
        Product restored = productRepository.save(product);

        auditService.record("PRODUCT_RESTORED", "product", restored.getId(),
                before, auditSnapshot(restored), null);

        return toResponseDto(restored);
    }

    // Employees must never receive a cost field, so the type differs rather than the value.
    private ProductResponseDTO toResponseDto(Product product) {
        return branchContext.isAdmin()
                ? productMapper.toAdminResponseDto(product)
                : productMapper.toResponseDto(product);
    }

    // The composite FK on (branch_id, category_id) would reject a foreign category anyway;
    // checking first turns that into a 404 that names the problem.
    private void requireCategoryInBranch(UUID categoryId) {
        if (categoryId != null && !categoryRepository.existsById(categoryId)) {
            throw new ResourceNotFoundException("Category", categoryId);
        }
    }

    /*
     * The category is recorded by name, not by id.
     *
     * An audit row is read by a person, months later, on a screen — and "Category: Beer" is a
     * fact where "categoryId: 01a061f0-418f-…" is a lookup nobody performs. The id is what the
     * database needs; the log's job is to be legible, and it is the only record of what a
     * product looked like before someone changed it.
     */
    private Map<String, Object> auditSnapshot(Product product) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", product.getName());
        snapshot.put("category", categoryNameOf(product.getCategoryId()));
        snapshot.put("sellingPrice", product.getSellingPrice());
        snapshot.put("isActive", product.getIsActive());
        snapshot.put("archivedAt", product.getArchivedAt());
        return snapshot;
    }

    // Uncategorised is a real state, and a category deleted since is not worth failing over.
    private String categoryNameOf(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findById(categoryId)
                .map(Category::getName)
                .orElse(null);
    }
}
