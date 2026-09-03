package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentPhotoResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.Payment;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.repository.PaymentRepository;
import com.supremebilliardshall.billiards_hall_system.service.PaymentPhotoService;
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
public class PaymentPhotoServiceImpl implements PaymentPhotoService {

    // The same allowlist the product-image route uses: the stored extension and the content
    // type served back are chosen from here, never from the browser-supplied filename. A photo
    // the ADMIN opens is served from this application's own origin, so an .html "photo" that
    // came back as text/html would execute as the ADMIN — this is what closes that.
    private static final Map<String, String> ALLOWED_TYPES = new LinkedHashMap<>(Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"));

    private final PaymentRepository paymentRepository;
    private final Path storageRoot;

    public PaymentPhotoServiceImpl(PaymentRepository paymentRepository,
                                   @Value("${supreme.payment-photo.path}") String storagePath) {
        this.paymentRepository = paymentRepository;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }


    @Override
    @Transactional
    public PaymentPhotoResponseDTO storePhoto(UUID paymentId, MultipartFile file) {
        Payment payment = requirePayment(paymentId);
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("The photo file is empty.");
        }
        if (payment.getPhotoPath() != null) {
            throw new BusinessRuleException("This payment already has a photo.");
        }

        String type = normalisedContentType(file.getContentType());
        String extension = ALLOWED_TYPES.get(type);
        if (extension == null) {
            throw new BusinessRuleException(
                    "That file is not an image the POS can accept. Use a JPEG, PNG or WebP.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("Could not read the photo: " + e.getMessage());
        }
        // The declared type is not enough: an HTML or script payload can claim image/png. The
        // bytes themselves must carry the magic number of the type they claim, or the file is
        // refused before anything is written.
        if (!magicMatches(type, bytes)) {
            throw new BusinessRuleException(
                    "That file is not a real JPEG, PNG or WebP image, whatever its name says.");
        }

        // The filename is built from the payment id and the allowlisted extension, never from
        // the upload: an attacker-chosen name is how a write escapes the storage directory.
        Path target = storageRoot.resolve(paymentId + "." + extension);
        try {
            Files.createDirectories(storageRoot);
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new BusinessRuleException("Could not store the photo: " + e.getMessage());
        }

        // Path plus checksum, so a backup that loses the volume is detectable rather than
        // silently empty.
        payment.setPhotoPath(target.toString());
        payment.setPhotoSha256(sha256(bytes));
        payment.setPhotoBytes((long) bytes.length);
        Payment saved = paymentRepository.saveAndFlush(payment);

        return new PaymentPhotoResponseDTO(saved.getId(), saved.getPhotoSha256(), saved.getPhotoBytes());
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] loadPhoto(UUID paymentId) {
        Payment payment = requirePayment(paymentId);
        if (payment.getPhotoPath() == null) {
            throw new ResourceNotFoundException("Photo for payment", paymentId);
        }
        try {
            return Files.readAllBytes(Path.of(payment.getPhotoPath()));
        } catch (IOException e) {
            throw new ResourceNotFoundException("Photo file for payment", paymentId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public String contentTypeOf(UUID paymentId) {
        Payment payment = requirePayment(paymentId);
        if (payment.getPhotoPath() == null) {
            throw new ResourceNotFoundException("Photo for payment", paymentId);
        }
        // Derived from the allowlisted extension the store wrote, never probed from disk: only
        // the three allowed image types are ever written, so the served type cannot be turned
        // into text/html by a crafted upload.
        String extension = extensionOf(payment.getPhotoPath());
        return ALLOWED_TYPES.entrySet().stream()
                .filter(entry -> entry.getValue().equals(extension))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("application/octet-stream");
    }

    private Payment requirePayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
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

    // The file signature for each allowed type. Checked against the type the upload claims, so
    // a mismatch — the classic "HTML renamed to .png" — is refused.
    private boolean magicMatches(String type, byte[] bytes) {
        return switch (type) {
            case "image/jpeg" -> startsWith(bytes, 0xFF, 0xD8, 0xFF);
            case "image/png" -> startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            // RIFF....WEBP: bytes 0-3 are "RIFF" and bytes 8-11 are "WEBP".
            case "image/webp" -> startsWith(bytes, 0x52, 0x49, 0x46, 0x46)
                    && bytes.length >= 12
                    && (bytes[8] & 0xFF) == 0x57 && (bytes[9] & 0xFF) == 0x45
                    && (bytes[10] & 0xFF) == 0x42 && (bytes[11] & 0xFF) == 0x50;
            default -> false;
        };
    }

    private boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
