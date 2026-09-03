package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.stock.*;
import com.supremebilliardshall.billiards_hall_system.entity.Product;
import com.supremebilliardshall.billiards_hall_system.entity.StockDelivery;
import com.supremebilliardshall.billiards_hall_system.entity.StockMovement;
import com.supremebilliardshall.billiards_hall_system.entity.StockReason;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.repository.BranchSettingRepository;
import com.supremebilliardshall.billiards_hall_system.repository.ProductRepository;
import com.supremebilliardshall.billiards_hall_system.repository.StockDeliveryRepository;
import com.supremebilliardshall.billiards_hall_system.repository.StockMovementRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.StockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class StockServiceImpl implements StockService {

    // The owner's configured threshold, if branch_setting has not been given one.
    private static final BigDecimal DEFAULT_LOW_STOCK_THRESHOLD = new BigDecimal("10");
    private static final String LOW_STOCK_THRESHOLD_KEY = "low_stock_threshold";

    private final StockMovementRepository stockMovementRepository;
    private final StockDeliveryRepository stockDeliveryRepository;
    private final ProductRepository productRepository;
    private final BranchSettingRepository branchSettingRepository;
    private final BranchContext branchContext;

    public StockServiceImpl(StockMovementRepository stockMovementRepository,
                            StockDeliveryRepository stockDeliveryRepository,
                            ProductRepository productRepository,
                            BranchSettingRepository branchSettingRepository,
                            BranchContext branchContext) {
        this.stockMovementRepository = stockMovementRepository;
        this.stockDeliveryRepository = stockDeliveryRepository;
        this.productRepository = productRepository;
        this.branchSettingRepository = branchSettingRepository;
        this.branchContext = branchContext;
    }


    @Override
    @Transactional
    public StockDeliveryResponseDTO receiveDelivery(StockDeliveryRequestDTO stockDeliveryRequestDTO) {
        StockDelivery delivery = new StockDelivery();
        delivery.setBranchId(branchContext.getCurrentBranchId());
        delivery.setSupplierName(stockDeliveryRequestDTO.getSupplierName());
        delivery.setReference(stockDeliveryRequestDTO.getReference());
        delivery.setNote(stockDeliveryRequestDTO.getNote());
        delivery.setReceivedBy(branchContext.getCurrentUserId());
        // Flushed before the movements: they reference it, and Hibernate cannot see the
        // dependency through a plain uuid column.
        StockDelivery savedDelivery = stockDeliveryRepository.saveAndFlush(delivery);

        List<StockMovementResponseDTO> movements = new ArrayList<>();
        for (StockDeliveryLineRequestDTO line : stockDeliveryRequestDTO.getLines()) {
            Product product = lockProduct(line.getProductId());

            // Cost first, from the quantity before this delivery lands.
            product.setAvgCost(newAverageCost(product, line.getQuantity(), line.getUnitCost()));

            // The delivery's note travels onto each movement it produces. The header row is
            // where it was typed, but the ledger is where it gets read — the audit feed lists
            // movements, and a delivery that arrives there with no context is the one row on
            // the screen that cannot say what it was.
            StockMovement movement = applyMovement(product, StockReason.DELIVERY, line.getQuantity(),
                    line.getUnitCost(), null, savedDelivery.getId(), savedDelivery.getNote());
            movements.add(toResponseDto(movement, product.getName()));
        }

        return new StockDeliveryResponseDTO(
                savedDelivery.getId(),
                savedDelivery.getSupplierName(),
                savedDelivery.getReference(),
                savedDelivery.getNote(),
                savedDelivery.getReceivedAt(),
                movements);
    }

    @Override
    @Transactional
    public StockMovementResponseDTO correctStock(StockCorrectionRequestDTO stockCorrectionRequestDTO) {
        Product product = lockProduct(stockCorrectionRequestDTO.getProductId());

        // The ledger records the difference, never the counted figure: replaying it must still
        // produce the same balance.
        BigDecimal delta = stockCorrectionRequestDTO.getNewQuantity().subtract(product.getQtyOnHand());
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessRuleException(
                    "Product '" + product.getName() + "' is already at that quantity; nothing to correct.");
        }

        StockMovement movement = applyMovement(product, StockReason.CORRECTION, delta,
                null, null, null, stockCorrectionRequestDTO.getNote());
        return toResponseDto(movement, product.getName());
    }

    @Override
    @Transactional
    public StockMovementResponseDTO compStock(StockCompRequestDTO stockCompRequestDTO) {
        Product product = lockProduct(stockCompRequestDTO.getProductId());

        StockMovement movement = applyMovement(product, StockReason.STAFF_COMP,
                stockCompRequestDTO.getQuantity().negate(), null, null, null, stockCompRequestDTO.getNote());
        return toResponseDto(movement, product.getName());
    }

    // One transaction for the whole basket. Each line still locks its own product row and
    // writes its own ledger movement — the batching is about atomicity, not about collapsing
    // four give-aways into one record.
    @Override
    @Transactional
    public List<StockMovementResponseDTO> compStockBatch(StockCompBatchRequestDTO stockCompBatchRequestDTO) {
        List<StockMovementResponseDTO> movements = new ArrayList<>();

        for (StockCompLineRequestDTO line : stockCompBatchRequestDTO.getLines()) {
            Product product = lockProduct(line.getProductId());
            if (product.getArchivedAt() != null) {
                throw new BusinessRuleException("Product '" + product.getName() + "' is archived.");
            }

            StockMovement movement = applyMovement(product, StockReason.STAFF_COMP,
                    line.getQuantity().negate(), null, null, null, stockCompBatchRequestDTO.getNote());
            movements.add(toResponseDto(movement, product.getName()));
        }

        return movements;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LowStockResponseDTO> getLowStock() {
        BigDecimal threshold = lowStockThreshold();
        return productRepository.findLowStock(threshold)
                .stream()
                .map(product -> new LowStockResponseDTO(
                        product.getId(), product.getName(),
                        product.getQtyOnHand(), threshold))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockMovementResponseDTO> getMovements(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        return stockMovementRepository.findByProductId(productId)
                .stream()
                .map(movement -> toResponseDto(movement, product.getName()))
                .toList();
    }

    @Override
    @Transactional
    public Product lockProduct(UUID productId) {
        return productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    @Override
    @Transactional
    public StockMovement applyMovement(Product lockedProduct, StockReason reason, BigDecimal quantityDelta,
                                       BigDecimal unitCost, UUID billLineId, UUID deliveryId, String note) {

        // qty_after is read from the locked row, so concurrent movements queue rather than
        // both computing the same balance.
        BigDecimal qtyAfter = lockedProduct.getQtyOnHand().add(quantityDelta);

        StockMovement movement = new StockMovement();
        movement.setBranchId(lockedProduct.getBranchId());
        movement.setProductId(lockedProduct.getId());
        movement.setReason(reason);
        movement.setQuantityDelta(quantityDelta);
        movement.setQtyAfter(qtyAfter);
        movement.setUnitCost(unitCost);
        movement.setBillLineId(billLineId);
        movement.setDeliveryId(deliveryId);
        movement.setNote(note);
        movement.setActorId(branchContext.getCurrentUserId());
        StockMovement savedMovement = stockMovementRepository.saveAndFlush(movement);

        // The cache, in the same transaction as the ledger row it mirrors. If these two ever
        // disagree, the ledger is right and this is the bug.
        lockedProduct.setQtyOnHand(qtyAfter);
        productRepository.saveAndFlush(lockedProduct);

        return savedMovement;
    }

    @Override
    public StockMovementResponseDTO toResponseDto(StockMovement movement, String productName) {
        StockMovementResponseDTO responseDto = branchContext.isAdmin()
                ? new StockMovementAdminResponseDTO()
                : new StockMovementResponseDTO();

        responseDto.setId(movement.getId());
        responseDto.setProductId(movement.getProductId());
        responseDto.setProductName(productName);
        responseDto.setReason(movement.getReason());
        responseDto.setQuantityDelta(movement.getQuantityDelta());
        responseDto.setQtyAfter(movement.getQtyAfter());
        responseDto.setBillLineId(movement.getBillLineId());
        responseDto.setDeliveryId(movement.getDeliveryId());
        responseDto.setNote(movement.getNote());
        responseDto.setOccurredAt(movement.getOccurredAt());
        responseDto.setBusinessDate(movement.getBusinessDate());

        if (responseDto instanceof StockMovementAdminResponseDTO adminResponseDto) {
            adminResponseDto.setUnitCost(movement.getUnitCost());
        }
        return responseDto;
    }


    // Moving weighted average, recalculated only when stock is received. The max(oldQty, 0)
    // guard matters because selling below zero is allowed: a negative quantity on hand would
    // otherwise produce a negative denominator and a nonsense cost.
    private BigDecimal newAverageCost(Product product, BigDecimal receivedQuantity, BigDecimal receivedUnitCost) {
        BigDecimal oldQty = product.getQtyOnHand();
        if (oldQty.compareTo(BigDecimal.ZERO) <= 0) {
            return receivedUnitCost.setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal oldValue = oldQty.multiply(product.getAvgCost());
        BigDecimal receivedValue = receivedQuantity.multiply(receivedUnitCost);
        return oldValue.add(receivedValue)
                .divide(oldQty.add(receivedQuantity), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal lowStockThreshold() {
        return branchSettingRepository.findValueByKey(LOW_STOCK_THRESHOLD_KEY)
                .map(BigDecimal::new)
                .orElse(DEFAULT_LOW_STOCK_THRESHOLD);
    }
}
