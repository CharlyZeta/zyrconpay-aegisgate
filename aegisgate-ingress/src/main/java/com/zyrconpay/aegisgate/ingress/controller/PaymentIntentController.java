package com.zyrconpay.aegisgate.ingress.controller;

import com.zyrconpay.aegisgate.common.dto.PaymentEvents.PaymentIntentEvent;
import com.zyrconpay.aegisgate.common.event.EventPublisher;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@CrossOrigin
@RestController
@RequestMapping("/api/v1/payments/intents")
@Tag(name = "Payment Intents", description = "Endpoints para el registro de la intención de pago (Checkout Intent)")
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
    @Operation(
            summary = "Registrar Checkout Intent",
            description = "Registra una intención de pago para iniciar el flujo de verificación 3DS. Publica un evento PAYMENT_INTENT_CREATED en la cola asíncrona de Kafka."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Intención de pago registrada y encolada correctamente"),
            @ApiResponse(responseCode = "400", description = "Datos de entrada incorrectos o malformados")
    })
    public Mono<ResponseEntity<Void>> registerIntent(
            @RequestHeader(value = "X-Merchant-ID", required = false)
            @Parameter(description = "Identificador único del comercio (Merchant)", example = "default-merchant")
            String merchantId,
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
