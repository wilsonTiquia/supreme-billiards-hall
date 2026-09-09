package com.supremebilliardshall.billiards_hall_system.service.impl;

/**
 * Whether the bytes are the image the upload claims they are.
 *
 * <p>The declared content type is a client assertion and nothing more: an HTML or script payload
 * can call itself image/png, and the browser will say so with a straight face. The file signature
 * is the only thing on the request that the sender does not choose freely, so it is what decides.
 *
 * <p>Shared by the product image and the payment photo rather than written twice. It was written
 * twice -- the payment photo checked the bytes and the product image did not, which is exactly how
 * a check that exists in two copies fails: not by both drifting, but by one never being written.
 * A file that survives this is still only an image, never something executed; the served content
 * type is derived from the allowlisted extension on the way back out.
 */
final class ImageSignature {

    private ImageSignature() {
    }

    /**
     * The file signature for each allowed type, checked against the type the upload claims. A
     * mismatch -- the classic "HTML renamed to .png" -- is false, as is any type not on the
     * allowlist the two callers share.
     */
    static boolean matches(String contentType, byte[] bytes) {
        return switch (contentType) {
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

    private static boolean startsWith(byte[] bytes, int... signature) {
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
}
