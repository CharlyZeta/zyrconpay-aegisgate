package com.zyrconpay.aegisgate.orchestrator.controller;

import com.zyrconpay.aegisgate.common.dto.VerificationEventSet;
import com.zyrconpay.aegisgate.common.exception.AegisGateException;
import com.zyrconpay.aegisgate.orchestrator.service.RedisStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin
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

    @PostMapping("/{transactionId}/authorize")
    public ResponseEntity<Map<String, Object>> authorize(@PathVariable String transactionId) {
        redisStateService.verifyAuthorization(transactionId);
        return ResponseEntity.ok(Map.of(
                "status", "AUTHORIZED",
                "message", "Transaction successfully verified and token consumed."
        ));
    }

    @ExceptionHandler(AegisGateException.class)
    public ResponseEntity<Map<String, Object>> handleAegisGateException(AegisGateException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "errorCode", ex.getErrorCode(),
                "message", ex.getMessage()
        ));
    }
}
