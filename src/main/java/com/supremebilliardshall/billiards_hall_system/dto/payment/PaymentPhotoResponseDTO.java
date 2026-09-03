package com.supremebilliardshall.billiards_hall_system.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentPhotoResponseDTO {

    private UUID paymentId;
    private String photoSha256;
    private Long photoBytes;
}
