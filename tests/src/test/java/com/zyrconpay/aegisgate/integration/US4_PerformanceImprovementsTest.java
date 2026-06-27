package com.zyrconpay.aegisgate.integration;

import com.zyrconpay.aegisgate.common.cache.MerchantProfileCache;
import com.zyrconpay.aegisgate.common.dto.VerificationEventSet;
import com.zyrconpay.aegisgate.orchestrator.service.RedisStateService;
import com.zyrconpay.aegisgate.orchestrator.service.TransactionStateRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class US4_PerformanceImprovementsTest {

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
        registry.add("spring.threads.virtual.enabled", () -> "true");
    }

    @Autowired
    private RedisStateService redisStateService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MerchantProfileCache merchantProfileCache;

    @Autowired
    private TransactionStateRegistry stateRegistry;

    @Test
    public void testRedisKeysUseHashtags() {
        String transactionId = "test-hashtag-123";
        
        // Add a token
        redisStateService.addToken(transactionId, "PAYMENT_INTENT_CREATED", 60);

        // Verify the key is stored in Redis using the hashtag format
        String expectedKey = "payment:3ds:events:{" + transactionId + "}";
        
        Set<String> keys = redisTemplate.keys("payment:3ds:events:*");
        assertThat(keys).contains(expectedKey);

        Boolean isMember = redisTemplate.opsForSet().isMember(expectedKey, "PAYMENT_INTENT_CREATED");
        assertThat(isMember).isTrue();
    }

    @Test
    public void testCaffeineCacheLoading() {
        // Retrieve credentials and verify caching
        Map<String, String> creds1 = merchantProfileCache.getCredentials("merchant-alpha");
        assertThat(creds1).isNotEmpty();
        assertThat(creds1.get("signingKey")).isEqualTo("alpha-key-secret");

        Map<String, String> creds2 = merchantProfileCache.getCredentials("merchant-alpha");
        assertThat(creds2).isSameAs(creds1);
    }

    @Test
    public void testSSEStateRegistryEmission() {
        String transactionId = "test-sse-123";

        Flux<VerificationEventSet> stream = stateRegistry.getStream(transactionId);

        // Setup step verifier to listen to stream
        StepVerifier.create(stream)
                .then(() -> {
                    // Simulate convergence that emits state
                    VerificationEventSet state = new VerificationEventSet(transactionId, true, true, "CONVERGED_VERIFIED");
                    stateRegistry.emit(transactionId, state);
                })
                .assertNext(state -> {
                    assertThat(state.transactionId()).isEqualTo(transactionId);
                    assertThat(state.status()).isEqualTo("CONVERGED_VERIFIED");
                })
                .expectComplete()
                .verify();
    }
}
