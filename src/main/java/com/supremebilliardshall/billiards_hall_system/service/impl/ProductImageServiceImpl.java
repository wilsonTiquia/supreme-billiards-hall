package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.product.ProductImageResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.Product;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.repository.ProductRepository;
import com.supremebilliardshall.billiards_hall_system.service.ProductImageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductImageServiceImpl implements ProductImageService {

    // The upload is a browser-declared content type, so the extension is chosen from this map
    // rather than from the filename: nothing an operator can name decides what is written or
    // what is later served back.
    private static final Map<String, String> ALLOWED_TYPES = new LinkedHashMap<>(Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"));

    // A product photo taken on a phone and scaled for a tile is well under this. The cap is
    // what keeps a 40 MB original off the volume and out of the nightly archive.
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private final ProductRepository productRepository;
    private final Path storageRoot;

    public ProductImageServiceImpl(ProductRepository productRepository,
                                   @Value("${supreme.product-image.path}") String storagePath) {
        this.productRepository = productRepository;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }

    @Override
    @Transactional
    public ProductImageResponseDTO storeImage(UUID productId, MultipartFile file) {
        Product product = requireProduct(productId);
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("The image file is empty.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessRuleException(
                    "That image is " + megabytes(file.getSize()) + " MB. The limit is 2 MB — "
                            + "scale it down and try again.");
        }

        String extension = ALLOWED_TYPES.get(normalisedContentType(file.getContentType()));
        if (extension == null) {
            throw new BusinessRuleException(
                    "That file is not an image the POS can show. Use a JPEG, PNG or WebP.");
        }

        // The filename is built from the product id, never from the upload: an attacker-chosen
        // name is how a write escapes the storage directory.
        Path target = storageRoot.resolve(productId + "." + extension);
        byte[] bytes;
        try {
            bytes = file.getBytes();
            Files.createDirectories(storageRoot);
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new BusinessRuleException("Could not store the image: " + e.getMessage());
        }

        // A replacement in a different format lands on a different path, so the file it
        // replaced would otherwise stay on the volume forever.
        deleteFileIfSuperseded(product.getImagePath(), target);

        product.setImagePath(target.toString());
        product.setImageSha256(sha256(bytes));
        product.setImageBytes((long) bytes.length);
        Product saved = productRepository.saveAndFlush(product);

        return new ProductImageResponseDTO(saved.getId(), saved.getImageSha256(), saved.getImageBytes());
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] loadImage(UUID productId) {
        Product product = requireImage(productId);
        try {
            return Files.readAllBytes(Path.of(product.getImagePath()));
        } catch (IOException e) {
            throw new ResourceNotFoundException("Image file for product", productId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public String contentTypeOf(UUID productId) {
        Product product = requireImage(productId);
        String extension = extensionOf(product.getImagePath());
        return ALLOWED_TYPES.entrySet().stream()
                .filter(entry -> entry.getValue().equals(extension))
                .map(Map.Entry::getKey)
                .findFirst()
                // Only the three allowed types are ever written, so this is unreachable for a
                // file this application stored. It matters for one dropped in by hand.
                .orElse("application/octet-stream");
    }

    @Override
    @Transactional(readOnly = true)
    public String checksumOf(UUID productId) {
        return requireImage(productId).getImageSha256();
    }

    @Override
    @Transactional
    public void deleteImage(UUID productId) {
        Product product = requireImage(productId);

        deleteFileIfSuperseded(product.getImagePath(), null);
        product.setImagePath(null);
        product.setImageSha256(null);
        product.setImageBytes(null);
        productRepository.save(product);
    }

    private Product requireProduct(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    private Product requireImage(UUID productId) {
        Product product = requireProduct(productId);
        if (product.getImagePath() == null) {
            throw new ResourceNotFoundException("Image for product", productId);
        }
        return product;
    }

    // A failed delete is not worth losing the upload over: the row already points at the new
    // file, and a stray old one costs a few kilobytes.
    private void deleteFileIfSuperseded(String previousPath, Path replacement) {
        if (previousPath == null) {
            return;
        }
        Path previous = Path.of(previousPath);
        if (previous.equals(replacement)) {
            return;
        }
        try {
            Files.deleteIfExists(previous);
        } catch (IOException ignored) {
            // Nothing the operator can act on, and the new image is already stored.
        }
    }

    private String normalisedContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        // Strips a "; charset=..." a client may have appended.
        int semicolon = contentType.indexOf(';');
        String bare = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return bare.trim().toLowerCase();
    }

    private String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
    }

    private String megabytes(long bytes) {
        return String.format("%.1f", bytes / 1024.0 / 1024.0);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
