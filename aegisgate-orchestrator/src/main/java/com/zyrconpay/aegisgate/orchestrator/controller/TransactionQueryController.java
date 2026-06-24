package com.zyrconpay.aegisgate.orchestrator.controller;

import com.zyrconpay.aegisgate.common.dto.VerificationEventSet;
import com.zyrconpay.aegisgate.orchestrator.service.RedisStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
public class TransactionQueryController {

    private final RedisStateService redisStateService;

    public TransactionQueryController(RedisStateService redisStateService) {
        this.redisStateService = redisStateService;
    }

    @GetMapping("/{transactionId}/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String transactionId) {
        VerificationEventSet state = redisStateService.getState(transactionId);
        
        return ResponseEntity.ok(Map.of(
                "transactionId", state.transactionId(),
                "hasPaymentIntent", state.hasPaymentIntent(),
                "hasWebhookReceived", state.hasWebhookReceived(),
                "status", state.status()
        ));
    }
}
