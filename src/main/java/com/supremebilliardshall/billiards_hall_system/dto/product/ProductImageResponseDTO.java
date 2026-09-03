package com.supremebilliardshall.billiards_hall_system.dto.product;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductImageResponseDTO {

    private UUID productId;
    private String imageSha256;
    private Long imageBytes;
}
