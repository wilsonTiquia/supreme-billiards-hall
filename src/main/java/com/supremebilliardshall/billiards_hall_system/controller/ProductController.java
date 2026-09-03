package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.product.ProductRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.product.ProductResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.stock.StockMovementResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.ProductService;
import com.supremebilliardshall.billiards_hall_system.service.StockService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService productService;
    private final StockService stockService;

    public ProductController (ProductService productService,
                              StockService stockService){
        this.productService = productService;
        this.stockService = stockService;
    }

    // Get All Products, optionally filtered by category, name or active state.
    // includeArchived is the admin catalogue's toggle; the service ignores it for an employee.
    @GetMapping
    public ResponseEntity<APIResponse<List<ProductResponseDTO>>>  getAll(@RequestParam(required = false) UUID categoryId,
                                                                        @RequestParam(required = false) String q,
                                                                        @RequestParam(defaultValue = "true") boolean activeOnly,
                                                                        @RequestParam(defaultValue = "false") boolean includeArchived){
        List<ProductResponseDTO> productDTOs = productService.searchProducts(categoryId, q, activeOnly, includeArchived);
        return ResponseEntity.ok(
            APIResponse.success(productDTOs, "All Products fetched successfully")
        );
    }

    // The ledger for one product: answers "why is this count wrong". Admin, because the
    // movements carry unit costs.
    @GetMapping("/{id}/movements")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<List<StockMovementResponseDTO>>> getMovements(@PathVariable UUID id) {
        List<StockMovementResponseDTO> movements = stockService.getMovements(id);
        return ResponseEntity.ok(
            APIResponse.success(movements, "Product movements fetched successfully")
        );
    }

    // insert a new product
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<ProductResponseDTO>> createProduct(@Valid @RequestBody ProductRequestDTO productRequestDTO) {
        ProductResponseDTO savedProduct = productService.createProduct(productRequestDTO);
        return ResponseEntity.ok(
            APIResponse.success(savedProduct, "Product created successfully")
        );
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<ProductResponseDTO>> updateProduct(@PathVariable UUID id,
                                                                         @Valid @RequestBody ProductRequestDTO productRequestDTO){
        ProductResponseDTO updatedProduct = productService.updateProduct(id, productRequestDTO);
        return ResponseEntity.ok(
            APIResponse.success(updatedProduct, "Product updated successfully")
        );
    }

    // The way back from an archive. Archiving is one click beside Edit, and without this the
    // only undo was SQL.
    @PostMapping("/{id}/unarchive")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<ProductResponseDTO>> unarchiveProduct(@PathVariable UUID id) {
        ProductResponseDTO restored = productService.unarchiveProduct(id);
        return ResponseEntity.ok(
            APIResponse.success(restored, "Product restored successfully")
        );
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<ProductResponseDTO>> deleteProduct(@PathVariable UUID id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok(
            APIResponse.success(null, "Product archived successfully with id: " + id)
        );
    }

}
