package com.mbi.couriertracking.courier.messaging;

import com.mbi.couriertracking.courier.api.CourierLocationInput;
import com.mbi.couriertracking.courier.application.CourierNotFoundException;
import com.mbi.couriertracking.courier.application.CourierService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourierLocationKafkaAdapterTests {

    private static final String VALID_PAYLOAD =
            "{\"time\":\"2026-10-21T12:00:00Z\",\"courier\":\"courier-1\",\"lat\":40.9923307,\"lng\":29.1244229}";
    private static final CourierLocationInput EXPECTED_INPUT = new CourierLocationInput(
            Instant.parse("2026-10-21T12:00:00Z"), "courier-1", 40.9923307, 29.1244229);

    @Mock
    private CourierService courierService;
    private CourierLocationKafkaAdapter listener;

    @BeforeEach
    void setUp() {
        listener = new CourierLocationKafkaAdapter(JsonMapper.builder().build(), new CourierLocationMessageMapperImpl(),
                Validation.buildDefaultValidatorFactory().getValidator(), courierService);
    }

    @Test
    void validPayloadReachesTheServiceAsInput() {
        listener.onMessage(record("courier-1", VALID_PAYLOAD));

        verify(courierService).submitLocation(EXPECTED_INPUT);
    }

    @Test
    void offsetTimeIsNormalizedToUtc() {
        listener.onMessage(record("courier-1", VALID_PAYLOAD.replace("2026-10-21T12:00:00Z", "2026-10-21T15:00:00+03:00")));

        ArgumentCaptor<CourierLocationInput> input = ArgumentCaptor.forClass(CourierLocationInput.class);
        verify(courierService).submitLocation(input.capture());
        assertEquals(Instant.parse("2026-10-21T12:00:00Z"), input.getValue().time());
    }

    @Test
    void courierFieldWinsOverRecordKey() {
        listener.onMessage(record("someone-else", VALID_PAYLOAD));

        verify(courierService).submitLocation(EXPECTED_INPUT);
    }

    @Test
    void malformedJsonIsRejectedWithoutCallingService() {
        assertThrows(JacksonException.class, () -> listener.onMessage(record("courier-1", "{not json")));

        verifyNoInteractions(courierService);
    }

    @Test
    void nullPayloadIsRejectedWithoutCallingService() {
        assertThrows(IllegalArgumentException.class, () -> listener.onMessage(record("courier-1", null)));

        verifyNoInteractions(courierService);
    }

    @Test
    void outOfRangeLatitudeIsRejectedBeforeTheService() {
        ConstraintViolationException error = assertThrows(ConstraintViolationException.class,
                () -> listener.onMessage(record("courier-1", VALID_PAYLOAD.replace("40.9923307", "95"))));

        assertThat(error.getConstraintViolations()).extracting(ConstraintViolation::getPropertyPath)
                .extracting(Object::toString).containsExactly("lat");
        verifyNoInteractions(courierService);
    }

    @Test
    void missingTimeIsRejectedBeforeTheService() {
        ConstraintViolationException error = assertThrows(ConstraintViolationException.class,
                () -> listener.onMessage(record("courier-1",
                        "{\"courier\":\"courier-1\",\"lat\":40.9923307,\"lng\":29.1244229}")));

        assertThat(error.getConstraintViolations()).extracting(ConstraintViolation::getPropertyPath)
                .extracting(Object::toString).containsExactly("time");
        verifyNoInteractions(courierService);
    }

    @Test
    void blankCourierIsRejectedBeforeTheService() {
        ConstraintViolationException error = assertThrows(ConstraintViolationException.class,
                () -> listener.onMessage(record("courier-1", VALID_PAYLOAD.replace("courier-1", "  "))));

        assertThat(error.getConstraintViolations()).extracting(ConstraintViolation::getPropertyPath)
                .extracting(Object::toString).containsExactly("courier");
        verifyNoInteractions(courierService);
    }

    @Test
    void unknownCourierExceptionPropagates() {
        doThrow(new CourierNotFoundException("courier-1")).when(courierService).submitLocation(any());

        assertThrows(CourierNotFoundException.class, () -> listener.onMessage(record("courier-1", VALID_PAYLOAD)));
    }

    private static ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("courier-locations", 0, 0L, key, value);
    }
}
