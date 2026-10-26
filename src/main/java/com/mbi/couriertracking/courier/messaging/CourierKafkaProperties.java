package com.mbi.couriertracking.courier.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka ingress settings; {@code enabled} is false unless a profile turns it on; the local profile does.
 */
@ConfigurationProperties(prefix = "courier.kafka")
public record CourierKafkaProperties(boolean enabled, String topic) {
}
