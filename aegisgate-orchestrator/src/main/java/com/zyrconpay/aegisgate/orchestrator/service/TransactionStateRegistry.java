package com.zyrconpay.aegisgate.orchestrator.service;

import com.zyrconpay.aegisgate.common.dto.VerificationEventSet;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TransactionStateRegistry {

    private final Map<String, Sinks.Many<VerificationEventSet>> sinks = new ConcurrentHashMap<>();

    /**
     * Obtains a hot flux for a transaction's state changes.
     *
     * @param transactionId the unique transaction identifier
     * @return the flux of updates
     */
    public Flux<VerificationEventSet> getStream(String transactionId) {
        return sinks.computeIfAbsent(transactionId, id -> 
            Sinks.many().multicast().directBestEffort()
        ).asFlux();
    }

    /**
     * Pushes a state update and completes the stream for the transaction.
     *
     * @param transactionId the transaction ID
     * @param state the converged or updated state
     */
    public void emit(String transactionId, VerificationEventSet state) {
        Sinks.Many<VerificationEventSet> sink = sinks.get(transactionId);
        if (sink != null) {
            sink.tryEmitNext(state);
            // If the state is final (CONVERGED_VERIFIED or CONVERGED_FAILED), complete the stream
            if ("CONVERGED_VERIFIED".equalsIgnoreCase(state.status()) || 
                "CONVERGED_FAILED".equalsIgnoreCase(state.status())) {
                sink.tryEmitComplete();
                sinks.remove(transactionId);
            }
        }
    }
}
