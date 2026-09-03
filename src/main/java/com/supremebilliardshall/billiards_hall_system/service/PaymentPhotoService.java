package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentPhotoResponseDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface PaymentPhotoService {
    // Writes the file to the configured volume and stores path, SHA-256 and byte count.
    PaymentPhotoResponseDTO storePhoto(UUID paymentId, MultipartFile file);

    byte[] loadPhoto(UUID paymentId);

    String contentTypeOf(UUID paymentId);
}
