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
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class US1_TransactionFlowTest {

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
    public void testHappyPath_IntentThenWebhook() {
        String transactionId = "89b9d311-64d6-444c-9742-b06c05d7b5b3";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-ID", "default-merchant");

        // 1. Register checkout intent
        Map<String, Object> intentBody = Map.of(
                "transaction_id", transactionId,
                "amount", 1500.0,
                "currency", "ARS"
        );
        HttpEntity<Map<String, Object>> intentEntity = new HttpEntity<>(intentBody, headers);
        ResponseEntity<Void> intentResponse = restTemplate.postForEntity(
                "/api/v1/payments/intents", 
                intentEntity, 
                Void.class
        );
        assertThat(intentResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 2. Simulate external webhook notification from Payway
        String webhookBody = "{\"transaction_id\":\"" + transactionId + "\",\"status\":\"approved\",\"verification\":{\"status_3ds\":\"SUCCESS\",\"eci\":\"05\"}}";
        String sig = computeSignature(webhookBody, "test-secret-key-123");
        
        HttpHeaders webhookHeaders = new HttpHeaders();
        webhookHeaders.setContentType(MediaType.APPLICATION_JSON);
        webhookHeaders.set("X-Merchant-ID", "default-merchant");
        webhookHeaders.set("X-Payway-Signature", sig);

        HttpEntity<String> webhookEntity = new HttpEntity<>(webhookBody, webhookHeaders);
        ResponseEntity<Void> webhookResponse = restTemplate.postForEntity(
                "/api/v1/gateways/payway/webhooks", 
                webhookEntity, 
                Void.class
        );
        assertThat(webhookResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 3. Verify status converges to verified (give some time for Kafka processing if needed, though Testcontainers/Kafka usually handles it fast)
        try { Thread.sleep(1000); } catch (InterruptedException e) {}
        
        ResponseEntity<Map> statusResponse = restTemplate.getForEntity(
                "/api/v1/payments/" + transactionId + "/status", 
                Map.class
        );
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody()).isNotNull();
        assertThat(statusResponse.getBody().get("status")).isEqualTo("CONVERGED_VERIFIED");
    }

    @Test
    public void testRaceCondition_WebhookArrivesBeforeIntent() {
        String transactionId = "89b9d311-64d6-444c-9742-b06c05d7b5b4";

        // 1. Webhook arrives first
        String webhookBody = "{\"transaction_id\":\"" + transactionId + "\",\"status\":\"approved\",\"verification\":{\"status_3ds\":\"SUCCESS\",\"eci\":\"05\"}}";
        String sig = computeSignature(webhookBody, "test-secret-key-123");
        
        HttpHeaders webhookHeaders = new HttpHeaders();
        webhookHeaders.setContentType(MediaType.APPLICATION_JSON);
        webhookHeaders.set("X-Merchant-ID", "default-merchant");
        webhookHeaders.set("X-Payway-Signature", sig);

        HttpEntity<String> webhookEntity = new HttpEntity<>(webhookBody, webhookHeaders);
        ResponseEntity<Void> webhookResponse = restTemplate.postForEntity(
                "/api/v1/gateways/payway/webhooks", 
                webhookEntity, 
                Void.class
        );
        assertThat(webhookResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 2. Intent registered subsequently within the window
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-ID", "default-merchant");

        Map<String, Object> intentBody = Map.of(
                "transaction_id", transactionId,
                "amount", 2000.0,
                "currency", "ARS"
        );
        HttpEntity<Map<String, Object>> intentEntity = new HttpEntity<>(intentBody, headers);
        ResponseEntity<Void> intentResponse = restTemplate.postForEntity(
                "/api/v1/payments/intents", 
                intentEntity, 
                Void.class
        );
        assertThat(intentResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 3. Verify status converges successfully
        try { Thread.sleep(1000); } catch (InterruptedException e) {}

        ResponseEntity<Map> statusResponse = restTemplate.getForEntity(
                "/api/v1/payments/" + transactionId + "/status", 
                Map.class
        );
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody()).isNotNull();
        assertThat(statusResponse.getBody().get("status")).isEqualTo("CONVERGED_VERIFIED");
    }
}
