package com.zyrconpay.aegisgate.orchestrator.consumer;

import com.zyrconpay.aegisgate.common.dto.PaymentEvents.PaymentIntentEvent;
import com.zyrconpay.aegisgate.common.dto.PaymentEvents.WebhookReceivedEvent;
import com.zyrconpay.aegisgate.orchestrator.service.RedisStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);

    private final RedisStateService redisStateService;

    public TransactionEventConsumer(RedisStateService redisStateService) {
        this.redisStateService = redisStateService;
    }

    @KafkaListener(topics = "payment-intents", groupId = "aegisgate-group")
    public void consumeIntent(PaymentIntentEvent event) {
        log.info("Consuming payment intent event for transaction: {}", event.transactionId());
        // Run execution within Virtual Thread context (virtual threads enabled globally via properties)
        Thread.startVirtualThread(() -> {
            redisStateService.addToken(event.transactionId(), "PAYMENT_INTENT_CREATED", 600);
        });
    }

    @KafkaListener(topics = "payment-webhooks", groupId = "aegisgate-group")
    public void consumeWebhook(WebhookReceivedEvent event) {
        log.info("Consuming webhook received event for transaction: {}", event.transactionId());
        // Run execution within Virtual Thread context (virtual threads enabled globally via properties)
        Thread.startVirtualThread(() -> {
            String token = "SUCCESS".equalsIgnoreCase(event.status3ds()) ? 
                           "3DS_WEBHOOK_RECEIVED:SUCCESS" : "3DS_WEBHOOK_RECEIVED:FAILED";
            redisStateService.addToken(event.transactionId(), token, 600);
        });
    }
}
