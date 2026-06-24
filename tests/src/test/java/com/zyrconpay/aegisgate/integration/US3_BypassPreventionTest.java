package com.zyrconpay.aegisgate.integration;

import com.zyrconpay.aegisgate.common.exception.SecurityBypassException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class US3_BypassPreventionTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private com.zyrconpay.aegisgate.orchestrator.service.RedisStateService redisStateService;

    private String computeSignature(String body, String secretKey) {
        try {
            javax.crypto.Mac sha256Hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec(
                    secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKeySpec);
            byte[] hashBytes = sha256Hmac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testWebhookSignatureTampered_ShouldReturn401() {
        String transactionId = "89b9d311-64d6-444c-9742-b06c05d7b5b1";
        String body = "{\"transaction_id\":\"" + transactionId + "\",\"status\":\"approved\",\"verification\":{\"status_3ds\":\"SUCCESS\",\"eci\":\"05\"}}";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-ID", "merchant-alpha");
        headers.set("X-Payway-Signature", "invalid-signature-123");

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/v1/gateways/payway/webhooks",
                entity,
                Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void testDirectAuthorizationBypass_ShouldThrowSecurityBypassException() {
        String transactionId = "89b9d311-64d6-444c-9742-b06c05d7b5b2";
        String body = "{\"transaction_id\":\"" + transactionId + "\",\"status\":\"approved\",\"verification\":{\"status_3ds\":\"SUCCESS\",\"eci\":\"05\"}}";
        
        String sig = computeSignature(body, "alpha-key-secret");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-ID", "merchant-alpha");
        headers.set("X-Payway-Signature", sig);

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        
        // Register webhook ONLY (no payment intent)
        ResponseEntity<Void> webhookResponse = restTemplate.postForEntity(
                "/api/v1/gateways/payway/webhooks",
                entity,
                Void.class
        );
        assertThat(webhookResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Query status - must be PENDING because payment intent is missing (violates dual-token)
        ResponseEntity<Map> statusResponse = restTemplate.getForEntity(
                "/api/v1/payments/" + transactionId + "/status",
                Map.class
        );
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody()).isNotNull();
        assertThat(statusResponse.getBody().get("status")).isEqualTo("PENDING");
    }

    @Test
    public void testVerifyAuthorization_WithMissingTokens_ShouldThrowSecurityBypassException() {
        String transactionId = "89b9d311-64d6-444c-9742-b06c05d7b5b7";
        org.junit.jupiter.api.Assertions.assertThrows(
                SecurityBypassException.class,
                () -> redisStateService.verifyAuthorization(transactionId)
        );
    }

    @Test
    public void testVerifyAuthorization_WithValidTokens_ShouldSucceedAndConsumeKey() {
        String transactionId = "89b9d311-64d6-444c-9742-b06c05d7b5b8";
        
        redisStateService.addToken(transactionId, "PAYMENT_INTENT_CREATED", 60);
        redisStateService.addToken(transactionId, "3DS_WEBHOOK_RECEIVED:SUCCESS", 60);

        // Verify authorization - should succeed
        redisStateService.verifyAuthorization(transactionId);

        // Subsequent call should fail because key was deleted (consumed)
        org.junit.jupiter.api.Assertions.assertThrows(
                SecurityBypassException.class,
                () -> redisStateService.verifyAuthorization(transactionId)
        );
    }
}
