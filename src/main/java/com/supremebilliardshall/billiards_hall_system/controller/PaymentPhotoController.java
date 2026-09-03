package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentPhotoResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.PaymentPhotoService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

// Photos are admin-only to read: they are a control on the counter, not a counter tool.
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentPhotoController {

    private final PaymentPhotoService paymentPhotoService;

    public PaymentPhotoController(PaymentPhotoService paymentPhotoService) {
        this.paymentPhotoService = paymentPhotoService;
    }

    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<APIResponse<PaymentPhotoResponseDTO>> uploadPhoto(@PathVariable UUID id,
                                                                            @RequestParam("file") MultipartFile file) {
        PaymentPhotoResponseDTO photo = paymentPhotoService.storePhoto(id, file);
        return ResponseEntity.
                ok(APIResponse.success(
                        photo,
                        "Payment photo stored successfully"));
    }

    // Returns the bytes themselves, so this is the one route that does not use the APIResponse
    // envelope — there is nothing to wrap an image in.
    @GetMapping("/{id}/photo")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> getPhoto(@PathVariable UUID id) {
        byte[] photo = paymentPhotoService.loadPhoto(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(paymentPhotoService.contentTypeOf(id)))
                .body(photo);
    }

}
