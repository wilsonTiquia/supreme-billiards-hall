package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.product.ProductImageResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.ProductImageService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.UUID;

// Unlike payment photos, reading is open to any authenticated user: the picture is how the
// counter finds the product, so an employee who cannot see it cannot work.
@RestController
@RequestMapping("/api/v1/products")
public class ProductImageController {

    private final ProductImageService productImageService;

    public ProductImageController(ProductImageService productImageService) {
        this.productImageService = productImageService;
    }

    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<ProductImageResponseDTO>> uploadImage(@PathVariable UUID id,
                                                                            @RequestParam("file") MultipartFile file) {
        ProductImageResponseDTO image = productImageService.storeImage(id, file);
        return ResponseEntity.
                ok(APIResponse.success(
                        image,
                        "Product image stored successfully"));
    }

    // Returns the bytes themselves, so this is the one route on this controller that does not
    // use the APIResponse envelope — there is nothing to wrap an image in.
    //
    // The grid asks for every tile it shows, on every poll. The checksum is the ETag, so a
    // repeat ask costs a 304 and no bytes, and a replaced image is still picked up at once.
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> getImage(@PathVariable UUID id, WebRequest request) {
        String checksum = productImageService.checksumOf(id);
        if (request.checkNotModified(checksum)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }

        byte[] image = productImageService.loadImage(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .eTag(checksum)
                .contentType(MediaType.parseMediaType(productImageService.contentTypeOf(id)))
                .body(image);
    }

    @DeleteMapping("/{id}/image")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<ProductImageResponseDTO>> deleteImage(@PathVariable UUID id) {
        productImageService.deleteImage(id);
        return ResponseEntity.ok(
                APIResponse.success(null, "Product image removed successfully with id: " + id)
        );
    }

}
