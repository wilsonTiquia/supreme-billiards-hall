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
import java.util.UUID;

@Service
public class PaymentPhotoServiceImpl implements PaymentPhotoService {

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

        // The filename is built from the payment id, never from the upload: an attacker-chosen
        // name is how a write escapes the storage directory.
        Path target = storageRoot.resolve(paymentId + extensionOf(file.getOriginalFilename()));
        byte[] bytes;
        try {
            bytes = file.getBytes();
            Files.createDirectories(storageRoot);
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new BusinessRuleException("Could not store the photo: " + e.getMessage());
        }

        // Path plus checksum, so a backup that lost the volume is detectable rather than
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
        try {
            String probed = Files.probeContentType(Path.of(payment.getPhotoPath()));
            return probed != null ? probed : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private Payment requirePayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        String extension = originalFilename.substring(dot + 1).toLowerCase();
        return extension.matches("[a-z0-9]{1,5}") ? "." + extension : "";
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
