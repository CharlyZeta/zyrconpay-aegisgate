package com.zyrconpay.aegisgate.ingress.controller;

import com.zyrconpay.aegisgate.common.cache.MerchantProfileCache;
import com.zyrconpay.aegisgate.common.dto.PaymentEvents.WebhookReceivedEvent;
import com.zyrconpay.aegisgate.common.event.EventPublisher;
import com.zyrconpay.aegisgate.ingress.service.SignatureValidationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/gateways/payway/webhooks")
public class WebhookController {

    private final EventPublisher eventPublisher;
    private final SignatureValidationService signatureValidationService;
    private final MerchantProfileCache merchantProfileCache;
    private final ObjectMapper objectMapper;

    public WebhookController(EventPublisher eventPublisher,
                             SignatureValidationService signatureValidationService,
                             MerchantProfileCache merchantProfileCache,
                             ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.signatureValidationService = signatureValidationService;
        this.merchantProfileCache = merchantProfileCache;
        this.objectMapper = objectMapper;
    }

    public record VerificationDetails(
            @JsonProperty("status_3ds") String status3ds,
            String eci
    ) {}

    public record WebhookRequest(
            @JsonProperty("transaction_id") String transactionId,
            String status,
            VerificationDetails verification
    ) {}

    @PostMapping
    public Mono<ResponseEntity<Void>> handleWebhook(
            @RequestHeader(value = "X-Merchant-ID", required = false) String merchantId,
            @RequestHeader(value = "X-Payway-Signature", required = false) String signature,
            @RequestBody String rawBody
    ) {
        return Mono.defer(() -> {
            if (merchantId == null || merchantId.isBlank() || signature == null || signature.isBlank()) {
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }

            Map<String, String> credentials = merchantProfileCache.getCredentials(merchantId);
            if (credentials == null || !credentials.containsKey("signingKey")) {
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }

            String secretKey = credentials.get("signingKey");

            boolean isValid = signatureValidationService.validateSignature(rawBody, signature, secretKey);

            if (!isValid) {
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }

            WebhookRequest request;
            try {
                request = objectMapper.readValue(rawBody, WebhookRequest.class);
            } catch (Exception e) {
                return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
            }

            if (request.transactionId() == null || request.verification() == null || request.verification().status3ds() == null) {
                return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
            }

            WebhookReceivedEvent event = new WebhookReceivedEvent(
                    request.transactionId(),
                    request.status(),
                    request.verification().status3ds(),
                    request.verification().eci(),
                    System.currentTimeMillis()
            );

            eventPublisher.publish("payment-webhooks", event.transactionId(), event);

            return Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED).build());
        });
    }
}
