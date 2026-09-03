package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.product.ProductImageResponseDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface ProductImageService {
    // Writes the file to the configured volume and stores path, SHA-256 and byte count.
    // Replacing an existing image deletes the file it replaces.
    ProductImageResponseDTO storeImage(UUID productId, MultipartFile file);

    byte[] loadImage(UUID productId);

    String contentTypeOf(UUID productId);

    // The stored SHA-256, used as the ETag before the bytes are read off the disk.
    String checksumOf(UUID productId);

    void deleteImage(UUID productId);
}
