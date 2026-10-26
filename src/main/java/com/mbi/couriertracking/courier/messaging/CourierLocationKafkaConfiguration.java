package com.mbi.couriertracking.courier.messaging;

import com.mbi.couriertracking.courier.application.CourierNotFoundException;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * Topic declaration and error handling for the Kafka ingress. The topic has one partition, matching the single
 * consumer thread of this increment (S010-D12). Messages that can never succeed are skipped at once;
 * anything else gets three attempts in total (two retries, one second apart) and is then skipped. A skipped record is logged once at INFO;
 * publishing it to a dead-letter topic is planned but not part of this increment (S010-D14).
 */
@Configuration
@ConditionalOnProperty(prefix = "courier.kafka", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CourierKafkaProperties.class)
@RequiredArgsConstructor
@Slf4j
public class CourierLocationKafkaConfiguration {

    private static final int PARTITIONS = 1;
    private static final long RETRY_INTERVAL_MILLIS = 1000L;
    private static final long MAX_RETRIES = 2L;

    private final CourierKafkaProperties properties;

    @Bean
    NewTopic courierLocationsTopic() {
        return TopicBuilder.name(properties.topic()).partitions(PARTITIONS).replicas(1).build();
    }

    /**
     * Logs the skipped record with identifiers only; the payload is never logged. This is where a dead-letter publish will go.
     */
    @Bean
    ConsumerRecordRecoverer skippedRecordRecoverer() {
        return (record, exception) -> log.info(
                "Skipped unprocessable location message topic={} partition={} offset={} key={} exception={}; "
                        + "publishing such records to a dead-letter topic is planned",
                record.topic(), record.partition(), record.offset(), record.key(), rootCause(exception).getClass().getName());
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(ConsumerRecordRecoverer recoverer) {
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MILLIS, MAX_RETRIES));
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class,
                ConstraintViolationException.class, CourierNotFoundException.class);
        handler.setLogLevel(KafkaException.Level.WARN);
        return handler;
    }

    private static Throwable rootCause(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
