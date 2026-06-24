package com.zyrconpay.aegisgate.common.event;

public interface EventPublisher {
    
    /**
     * Publishes an event to the specified topic/queue.
     *
     * @param topic the destination topic
     * @param key the message key (usually transaction ID for partition routing)
     * @param payload the message body/payload
     */
    void publish(String topic, String key, Object payload);
}
