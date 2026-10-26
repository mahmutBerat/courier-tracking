package com.mbi.couriertracking.courier.messaging;

import com.mbi.couriertracking.courier.application.CourierNotFoundException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class CourierLocationKafkaConfigurationTests {

    private final CourierKafkaProperties properties = new CourierKafkaProperties(true, "courier-locations");
    private final CourierLocationKafkaConfiguration configuration = new CourierLocationKafkaConfiguration(properties);

    @Test
    void topicIsDeclaredWithOnePartition() {
        NewTopic topic = configuration.courierLocationsTopic();

        assertEquals("courier-locations", topic.name());
        assertEquals(1, topic.numPartitions());
        assertEquals(1, topic.replicationFactor());
    }

    @Test
    void errorHandlerIsCreatedWithTheSkipRecoverer() {
        DefaultErrorHandler handler = configuration.kafkaErrorHandler(configuration.skippedRecordRecoverer());

        assertNotNull(handler);
    }

    @Test
    void skippedRecordIsLoggedOnceAtInfoWithIdentifiersAndRootCause(CapturedOutput output) {
        ConsumerRecordRecoverer recoverer = configuration.skippedRecordRecoverer();
        var failed = new ConsumerRecord<>("courier-locations", 2, 17L, "courier-1", "{not json");
        var exception = new ListenerExecutionFailedException("listener failed", new CourierNotFoundException("courier-1"));

        recoverer.accept(failed, exception);

        String log = output.getOut();
        assertTrue(log.contains("INFO"));
        assertTrue(log.contains("topic=courier-locations partition=2 offset=17 key=courier-1"));
        assertTrue(log.contains("exception=com.mbi.couriertracking.courier.application.CourierNotFoundException"));
        assertTrue(log.contains("dead-letter topic"));
        assertEquals(1, log.split("Skipped unprocessable location message", -1).length - 1);
        assertTrue(!log.contains("{not json"), "payload must not be logged");
    }
}
