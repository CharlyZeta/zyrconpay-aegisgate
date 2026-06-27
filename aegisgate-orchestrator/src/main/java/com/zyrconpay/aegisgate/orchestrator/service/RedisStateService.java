package com.zyrconpay.aegisgate.orchestrator.service;

import com.zyrconpay.aegisgate.common.dto.VerificationEventSet;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class RedisStateService {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> convergenceScript;
    private final TransactionStateRegistry stateRegistry;

    public RedisStateService(StringRedisTemplate redisTemplate, TransactionStateRegistry stateRegistry) {
        this.redisTemplate = redisTemplate;
        this.stateRegistry = stateRegistry;
        
        // Load Lua script from resources
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/converge.lua"));
        script.setResultType(Long.class);
        this.convergenceScript = script;
    }

    /**
     * Atomically adds a token to the transaction's event set, sets its TTL, and checks for convergence.
     *
     * @param transactionId the unique transaction identifier
     * @param token the verification token (e.g., PAYMENT_INTENT_CREATED, 3DS_WEBHOOK_RECEIVED)
     * @param ttlSeconds validity window in seconds
     * @return the current VerificationEventSet state
     */
    public VerificationEventSet addToken(String transactionId, String token, int ttlSeconds) {
        String key = "payment:3ds:events:{" + transactionId + "}";
        List<String> keys = Collections.singletonList(key);
        
        // Execute converge.lua
        Long result = redisTemplate.execute(convergenceScript, keys, token, String.valueOf(ttlSeconds));
        
        VerificationEventSet state;
        if (result != null && result == 1L) {
            state = new VerificationEventSet(transactionId, true, true, "CONVERGED_VERIFIED");
        } else if (result != null && result == 2L) {
            state = new VerificationEventSet(transactionId, true, true, "CONVERGED_FAILED");
        } else {
            // Not converged yet: query the set to see which token is present
            Boolean hasIntent = redisTemplate.opsForSet().isMember(key, "PAYMENT_INTENT_CREATED");
            Boolean hasSuccess = redisTemplate.opsForSet().isMember(key, "3DS_WEBHOOK_RECEIVED:SUCCESS");
            Boolean hasFailed = redisTemplate.opsForSet().isMember(key, "3DS_WEBHOOK_RECEIVED:FAILED");
            
            state = new VerificationEventSet(
                    transactionId,
                    Boolean.TRUE.equals(hasIntent),
                    Boolean.TRUE.equals(hasSuccess) || Boolean.TRUE.equals(hasFailed),
                    "PENDING"
            );
        }

        // Emit state to registry for real-time subscribers
        stateRegistry.emit(transactionId, state);
        
        return state;
    }

    /**
     * Retrieves the current event set state for a transaction.
     *
     * @param transactionId the unique transaction identifier
     * @return the VerificationEventSet state
     */
    public VerificationEventSet getState(String transactionId) {
        String key = "payment:3ds:events:{" + transactionId + "}";
        Boolean hasIntent = redisTemplate.opsForSet().isMember(key, "PAYMENT_INTENT_CREATED");
        Boolean hasSuccess = redisTemplate.opsForSet().isMember(key, "3DS_WEBHOOK_RECEIVED:SUCCESS");
        Boolean hasFailed = redisTemplate.opsForSet().isMember(key, "3DS_WEBHOOK_RECEIVED:FAILED");

        if (Boolean.TRUE.equals(hasIntent) && Boolean.TRUE.equals(hasSuccess)) {
            return new VerificationEventSet(transactionId, true, true, "CONVERGED_VERIFIED");
        } else if (Boolean.TRUE.equals(hasIntent) && Boolean.TRUE.equals(hasFailed)) {
            return new VerificationEventSet(transactionId, true, true, "CONVERGED_FAILED");
        } else {
            return new VerificationEventSet(
                    transactionId,
                    Boolean.TRUE.equals(hasIntent),
                    Boolean.TRUE.equals(hasSuccess) || Boolean.TRUE.equals(hasFailed),
                    "PENDING"
            );
        }
    }

    /**
     * Verifies that the transaction is fully converged and deletes/marks the set.
     * Throws SecurityBypassException if either token is missing.
     *
     * @param transactionId the unique transaction identifier
     */
    public void verifyAuthorization(String transactionId) {
        String key = "payment:3ds:events:{" + transactionId + "}";
        List<String> keys = java.util.Collections.singletonList(key);
        Long result = redisTemplate.execute(convergenceScript, keys, "AUTHORIZE_CHECK", "600");
        if (result != null && result == -1L) {
            throw new com.zyrconpay.aegisgate.common.exception.SecurityBypassException(
                    "Security bypass detected: missing required verification tokens for transaction " + transactionId);
        } else if (result != null && result == -2L) {
            throw new com.zyrconpay.aegisgate.common.exception.SecurityBypassException(
                    "Transaction verification failed: transaction " + transactionId + " was rejected by the bank 3DS check.");
        }
    }
}
