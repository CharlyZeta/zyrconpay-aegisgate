package com.zyrconpay.aegisgate.orchestrator.controller;

import com.zyrconpay.aegisgate.common.dto.VerificationEventSet;
import com.zyrconpay.aegisgate.common.exception.AegisGateException;
import com.zyrconpay.aegisgate.orchestrator.service.RedisStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments & Status", description = "Endpoints para la consulta de convergencia de estado y autorización final de transacciones")
public class TransactionQueryController {

    private final RedisStateService redisStateService;

    public TransactionQueryController(RedisStateService redisStateService) {
        this.redisStateService = redisStateService;
    }

    @GetMapping("/{transactionId}/status")
    @Operation(
            summary = "Consultar Estado de Transacción",
            description = "Consulta en tiempo real en Redis el estado convergido y los tokens recibidos para una transacción específica."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Estado recuperado exitosamente"),
            @ApiResponse(responseCode = "404", description = "Transacción no encontrada")
    })
    public ResponseEntity<Map<String, Object>> getStatus(
            @PathVariable
            @Parameter(description = "ID único de la transacción (UUID)", example = "tx-flow-happy-999")
            String transactionId
    ) {
        VerificationEventSet state = redisStateService.getState(transactionId);
        
        return ResponseEntity.ok(Map.of(
                "transactionId", state.transactionId(),
                "hasPaymentIntent", state.hasPaymentIntent(),
                "hasWebhookReceived", state.hasWebhookReceived(),
                "status", state.status()
        ));
    }

    @PostMapping("/{transactionId}/authorize")
    @Operation(
            summary = "Autorizar y Consumir Token de Checkout",
            description = "Punto de control de captura/autorización final. Verifica que la transacción esté en estado `CONVERGED_VERIFIED` y consume atómicamente sus tokens en Redis para evitar replay attacks y bypass."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Transacción autorizada y tokens consumidos exitosamente"),
            @ApiResponse(responseCode = "403", description = "Intento de bypass o estado no convergido (SecurityBypassException)"),
            @ApiResponse(responseCode = "404", description = "Transacción no encontrada")
    })
    public ResponseEntity<Map<String, Object>> authorize(
            @PathVariable
            @Parameter(description = "ID único de la transacción (UUID)", example = "tx-flow-happy-999")
            String transactionId
    ) {
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
