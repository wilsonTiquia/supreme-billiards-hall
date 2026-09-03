package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.stock.*;
import com.supremebilliardshall.billiards_hall_system.service.StockService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    // Admin: a delivery carries unit costs and moves the average cost.
    @PostMapping("/deliveries")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<StockDeliveryResponseDTO>> receiveDelivery(@Valid @RequestBody StockDeliveryRequestDTO stockDeliveryRequestDTO) {
        StockDeliveryResponseDTO delivery = stockService.receiveDelivery(stockDeliveryRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        delivery,
                        "Delivery received successfully"));
    }

    // Admin: overriding a counted quantity is not a counter action.
    @PostMapping("/corrections")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<StockMovementResponseDTO>> correctStock(@Valid @RequestBody StockCorrectionRequestDTO stockCorrectionRequestDTO) {
        StockMovementResponseDTO movement = stockService.correctStock(stockCorrectionRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        movement,
                        "Stock corrected successfully"));
    }

    // Staff comps happen at the counter, and the note plus the actor are what make them safe.
    @PostMapping("/comps")
    public ResponseEntity<APIResponse<StockMovementResponseDTO>> compStock(@Valid @RequestBody StockCompRequestDTO stockCompRequestDTO) {
        StockMovementResponseDTO movement = stockService.compStock(stockCompRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        movement,
                        "Staff comp recorded successfully"));
    }

    // A round for a table, or a tray for the staff: several products given away at once. One
    // transaction, so a basket of four never half-commits.
    @PostMapping("/comps/batch")
    public ResponseEntity<APIResponse<List<StockMovementResponseDTO>>> compStockBatch(@Valid @RequestBody StockCompBatchRequestDTO stockCompBatchRequestDTO) {
        List<StockMovementResponseDTO> movements = stockService.compStockBatch(stockCompBatchRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        movements,
                        "Give-away recorded successfully"));
    }

    @GetMapping("/low")
    public ResponseEntity<APIResponse<List<LowStockResponseDTO>>> getLowStock() {
        List<LowStockResponseDTO> lowStock = stockService.getLowStock();
        return ResponseEntity.
                ok(APIResponse.success(
                        lowStock,
                        "Low stock fetched successfully"));
    }

}
