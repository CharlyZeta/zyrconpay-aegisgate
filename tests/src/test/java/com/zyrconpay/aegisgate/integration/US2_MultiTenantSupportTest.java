package com.zyrconpay.aegisgate.integration;

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
public class US2_MultiTenantSupportTest {

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
    public void testValidMerchantRequest_RetrievesCorrectKeyFromVault() {
        String transactionId = "89b9d311-64d6-444c-9742-b06c05d7b5b5";
        String body = "{\"transaction_id\":\"" + transactionId + "\",\"status\":\"approved\",\"verification\":{\"status_3ds\":\"SUCCESS\",\"eci\":\"05\"}}";
        
        String sig = computeSignature(body, "alpha-key-secret");
        
        HttpHeaders headers1 = new HttpHeaders();
        headers1.setContentType(MediaType.APPLICATION_JSON);
        headers1.set("X-Merchant-ID", "merchant-alpha");
        headers1.set("X-Payway-Signature", sig);
        
        HttpEntity<String> entity1 = new HttpEntity<>(body, headers1);

        ResponseEntity<Void> response1 = restTemplate.exchange(
                "/api/v1/gateways/payway/webhooks",
                HttpMethod.POST,
                entity1,
                Void.class
        );
        
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    public void testMissingOrUnrecognizedMerchantHeader_ShouldReject() {
        String transactionId = "89b9d311-64d6-444c-9742-b06c05d7b5b6";
        String body = "{\"transaction_id\":\"" + transactionId + "\",\"status\":\"approved\",\"verification\":{\"status_3ds\":\"SUCCESS\",\"eci\":\"05\"}}";
        
        // 1. Missing header (missing merchant ID)
        HttpHeaders headersMissing = new HttpHeaders();
        headersMissing.setContentType(MediaType.APPLICATION_JSON);
        headersMissing.set("X-Payway-Signature", "any-signature");
        HttpEntity<String> entityMissing = new HttpEntity<>(body, headersMissing);
        
        ResponseEntity<Void> responseMissing = restTemplate.exchange(
                "/api/v1/gateways/payway/webhooks",
                HttpMethod.POST,
                entityMissing,
                Void.class
        );
        assertThat(responseMissing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 2. Unrecognized merchant ID
        HttpHeaders headersUnrecognized = new HttpHeaders();
        headersUnrecognized.setContentType(MediaType.APPLICATION_JSON);
        headersUnrecognized.set("X-Merchant-ID", "unknown-merchant-id");
        headersUnrecognized.set("X-Payway-Signature", "any-signature");
        HttpEntity<String> entityUnrecognized = new HttpEntity<>(body, headersUnrecognized);
        
        ResponseEntity<Void> responseUnrecognized = restTemplate.exchange(
                "/api/v1/gateways/payway/webhooks",
                HttpMethod.POST,
                entityUnrecognized,
                Void.class
        );
        assertThat(responseUnrecognized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
