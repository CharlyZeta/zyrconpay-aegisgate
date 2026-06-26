package com.zyrconpay.aegisgate.ingress.controller;

import com.zyrconpay.aegisgate.common.cache.MerchantProfileCache;
import com.zyrconpay.aegisgate.common.dto.PaymentEvents.WebhookReceivedEvent;
import com.zyrconpay.aegisgate.common.event.EventPublisher;
import com.zyrconpay.aegisgate.ingress.service.SignatureValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

@CrossOrigin
@RestController
@RequestMapping("/api/v1/gateways/payway/webhooks")
@Tag(name = "Webhooks", description = "Endpoints para la recepción de callbacks/webhooks asíncronos desde Payway")
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
    @Operation(
            summary = "Procesar Webhook de Payway",
            description = "Recibe notificaciones asíncronas de Payway con el estado de autenticación 3DS. Valida la firma HMAC-SHA256 en la cabecera `X-Payway-Signature` con la clave secreta del comercio obtenida desde HashiCorp Vault. Si es válida, publica el evento 3DS_WEBHOOK_RECEIVED en Kafka."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Webhook recibido, verificado criptográficamente y encolado correctamente"),
            @ApiResponse(responseCode = "401", description = "No autorizado: firma HMAC inválida, cabeceras ausentes, o comercio no registrado"),
            @ApiResponse(responseCode = "400", description = "Carga útil malformada o faltan campos obligatorios")
    })
    public Mono<ResponseEntity<Void>> handleWebhook(
            @RequestHeader(value = "X-Merchant-ID", required = false)
            @Parameter(description = "Identificador único del comercio (Merchant)", example = "default-merchant")
            String merchantId,
            @RequestHeader(value = "X-Payway-Signature", required = false)
            @Parameter(description = "Firma criptográfica HMAC-SHA256 del cuerpo del mensaje calculada usando la clave secreta (signingKey) del comercio", example = "a5940428d085954a782b535d46114b7891104e768e1a123e42d7658ba3135bf7")
            String signature,
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
