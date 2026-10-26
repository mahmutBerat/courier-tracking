package com.mbi.couriertracking.courier.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka ingress settings; {@code enabled} is false unless the kafka profile turns it on.
 */
@ConfigurationProperties(prefix = "courier.kafka")
public record CourierKafkaProperties(boolean enabled, String topic) {
}
