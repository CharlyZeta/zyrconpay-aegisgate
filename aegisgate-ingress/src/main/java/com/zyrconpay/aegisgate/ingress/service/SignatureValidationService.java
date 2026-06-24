package com.zyrconpay.aegisgate.ingress.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class SignatureValidationService {

    /**
     * Validates an incoming webhook signature using SHA256-HMAC.
     *
     * @param rawBody the raw request body
     * @param headerSignature the signature sent by the gateway
     * @param secretKey the merchant's secret key
     * @return true if the signature is valid, false otherwise
     */
    public boolean validateSignature(String rawBody, String headerSignature, String secretKey) {
        if (rawBody == null || headerSignature == null || secretKey == null) {
            return false;
        }
        
        try {
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKeySpec);
            
            byte[] computedHashBytes = sha256Hmac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            // Hex format formatting
            String computedHash = HexFormat.of().formatHex(computedHashBytes);

            // Constant-time comparison to prevent timing attacks
            return MessageDigest.isEqual(
                    computedHash.getBytes(StandardCharsets.UTF_8),
                    headerSignature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }
}
