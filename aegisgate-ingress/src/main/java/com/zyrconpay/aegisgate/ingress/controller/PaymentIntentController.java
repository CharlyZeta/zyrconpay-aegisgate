package com.zyrconpay.aegisgate.ingress.controller;

import com.zyrconpay.aegisgate.common.dto.PaymentEvents.PaymentIntentEvent;
import com.zyrconpay.aegisgate.common.event.EventPublisher;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/payments/intents")
public class PaymentIntentController {

    private final EventPublisher eventPublisher;

    public PaymentIntentController(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public record PaymentIntentRequest(
            @JsonProperty("transaction_id") String transactionId,
            double amount,
            String currency
    ) {}

    @PostMapping
    public Mono<ResponseEntity<Void>> registerIntent(
            @RequestHeader(value = "X-Merchant-ID", required = false) String merchantId,
            @RequestBody PaymentIntentRequest request
    ) {
        return Mono.fromRunnable(() -> {
            PaymentIntentEvent event = new PaymentIntentEvent(
                    request.transactionId(),
                    request.amount(),
                    request.currency(),
                    merchantId != null ? merchantId : "default-merchant",
                    System.currentTimeMillis()
            );
            eventPublisher.publish("payment-intents", event.transactionId(), event);
        }).thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).build());
    }
}
