package com.mbi.couriertracking.courier.messaging;

import java.time.Instant;

/**
 * JSON payload of a courier location message as produced on the Kafka topic (S010 message contract).
 */
public record CourierLocationMessage(Instant time, String courier, Double lat, Double lng) {
}
