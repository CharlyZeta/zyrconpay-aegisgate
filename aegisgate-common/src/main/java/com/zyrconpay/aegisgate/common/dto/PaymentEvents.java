package com.zyrconpay.aegisgate.common.dto;

import java.io.Serializable;

public final class PaymentEvents {

    private PaymentEvents() {
        // Private constructor to prevent instantiation of utility/container class
    }

    public record PaymentIntentEvent(
            String transactionId,
            double amount,
            String currency,
            String merchantId,
            long timestamp
    ) implements Serializable {}

    public record WebhookReceivedEvent(
            String transactionId,
            String status,
            String status3ds,
            String eci,
            long timestamp
    ) implements Serializable {}
}
