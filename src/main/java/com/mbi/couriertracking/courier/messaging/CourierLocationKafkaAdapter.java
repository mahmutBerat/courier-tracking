package com.mbi.couriertracking.courier.messaging;

import com.mbi.couriertracking.courier.api.CourierLocationInput;
import com.mbi.couriertracking.courier.application.CourierService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

/**
 * Inbound adapter: turns a Kafka record into a {@link CourierLocationInput} and hands it to the same use case as the HTTP endpoint.
 * Parse, mapping, and validation failures are thrown as is; the error handler decides whether they are retried or dead-lettered.
 */
@Component
@ConditionalOnProperty(prefix = "courier.kafka", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class CourierLocationKafkaAdapter {

    private final JsonMapper jsonMapper;
    private final CourierLocationMessageMapper messageMapper;
    private final Validator validator;
    private final CourierService courierService;

    @KafkaListener(topics = "${courier.kafka.topic}")
    public void onMessage(ConsumerRecord<String, String> consumerRecord) {
        if (consumerRecord.value() == null) {
            throw new IllegalArgumentException("Empty payload");
        }

        CourierLocationInput input = readMessageContent(consumerRecord);

        log.debug("Processing location message key={} partition={} offset={}", consumerRecord.key(), consumerRecord.partition(), consumerRecord.offset());
        courierService.submitLocation(input);
    }

    private CourierLocationInput readMessageContent(ConsumerRecord<String, String> consumerRecord) {
        CourierLocationMessage message = jsonMapper.readValue(consumerRecord.value(), CourierLocationMessage.class);
        CourierLocationInput input = messageMapper.toInput(message);
        Set<ConstraintViolation<CourierLocationInput>> violations = validator.validate(input);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations); // TODO send broken payload to DLT
        }
        return input;
    }
}
