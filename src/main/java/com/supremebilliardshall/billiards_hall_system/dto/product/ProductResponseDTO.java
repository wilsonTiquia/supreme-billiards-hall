package com.supremebilliardshall.billiards_hall_system.dto.product;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// What an EMPLOYEE receives. It carries no cost field at all — not a null one — so cost
// cannot leak through this endpoint regardless of what the UI does.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponseDTO {

    private UUID id;
    private String name;
    private UUID categoryId;
    private BigDecimal sellingPrice;
    private BigDecimal qtyOnHand;
    private Boolean isActive;
    // Null for a live product. Set means archived, and the admin catalogue shows the date so
    // a misclick from Thursday is recognisable as one.
    private OffsetDateTime archivedAt;
    // Null when the product has no picture. Doubles as the cache-buster on the image URL, so
    // a replaced image is seen at once while an unchanged one is served from cache.
    private String imageSha256;
}
