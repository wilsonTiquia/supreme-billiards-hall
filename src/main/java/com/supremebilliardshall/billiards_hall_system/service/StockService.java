package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.stock.*;
import com.supremebilliardshall.billiards_hall_system.entity.Product;
import com.supremebilliardshall.billiards_hall_system.entity.StockMovement;
import com.supremebilliardshall.billiards_hall_system.entity.StockReason;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface StockService {
    StockDeliveryResponseDTO receiveDelivery(StockDeliveryRequestDTO stockDeliveryRequestDTO);

    StockMovementResponseDTO correctStock(StockCorrectionRequestDTO stockCorrectionRequestDTO);

    StockMovementResponseDTO compStock(StockCompRequestDTO stockCompRequestDTO);

    // Several products in one act, in one transaction. All or nothing: a basket of four must
    // never half-commit and leave the ledger describing a give-away that did not happen.
    List<StockMovementResponseDTO> compStockBatch(StockCompBatchRequestDTO stockCompBatchRequestDTO);

    List<LowStockResponseDTO> getLowStock();

    List<StockMovementResponseDTO> getMovements(UUID productId);

    // Takes SELECT ... FOR UPDATE on the product. Every caller that is about to move stock
    // must go through this first, so the running balance cannot be computed from a stale read.
    Product lockProduct(UUID productId);

    // Posts one ledger row and updates the cached quantity in the same transaction. The
    // product must already be locked by lockProduct.
    StockMovement applyMovement(Product lockedProduct, StockReason reason, BigDecimal quantityDelta,
                               BigDecimal unitCost, UUID billLineId, UUID deliveryId, String note);

    StockMovementResponseDTO toResponseDto(StockMovement movement, String productName);
}
