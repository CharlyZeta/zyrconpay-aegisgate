package com.zyrconpay.aegisgate.common.dto;

import java.io.Serializable;

public record VerificationEventSet(
        String transactionId,
        boolean hasPaymentIntent,
        boolean hasWebhookReceived,
        String status
) implements Serializable {

    public boolean isConverged() {
        return hasPaymentIntent && hasWebhookReceived;
    }
}
