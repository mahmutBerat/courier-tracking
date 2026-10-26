package com.mbi.couriertracking.courier.messaging;

import com.mbi.couriertracking.KafkaTestcontainersConfiguration;
import com.mbi.couriertracking.TestcontainersConfiguration;
import com.mbi.couriertracking.common.geo.GeoLocation;
import com.mbi.couriertracking.common.geo.HaversineDistanceCalculatorStrategy;
import com.mbi.couriertracking.courier.application.CourierService;
import com.mbi.couriertracking.courier.domain.Courier;
import com.mbi.couriertracking.courier.persistence.CourierLocationRepository;
import com.mbi.couriertracking.courier.persistence.CourierRepository;
import com.mbi.couriertracking.store.domain.StoreVisit;
import com.mbi.couriertracking.store.persistence.StoreVisitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * S010-AC01–AC10 against a real broker and database; skipped records are recognized by their INFO log line and key.
 */
@SpringBootTest(properties = "courier.kafka.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class CourierLocationKafkaIntegrationTests {

    private static final String TOPIC = "courier-locations";
    private static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(20);
    private static final GeoLocation ATASEHIR = new GeoLocation(40.9923307, 29.1244229);
    private static final GeoLocation NOVADA = new GeoLocation(40.986106, 29.1161293);
    private static final String FIRST_TIME = "2026-10-21T12:00:00Z";
    private static final String SECOND_TIME = "2026-10-21T12:01:00Z";

    @Autowired
    private KafkaTemplate<Object, Object> producer;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private CourierRepository courierRepository;
    @Autowired
    private CourierLocationRepository courierLocationRepository;
    @Autowired
    private StoreVisitRepository storeVisitRepository;
    @MockitoSpyBean
    private CourierService courierService;

    @BeforeEach
    void clearState() {
        courierLocationRepository.deleteAllInBatch();
        courierRepository.deleteAllInBatch(); // cascades to store visits
    }

    @AfterEach
    void resetSpy() {
        reset(courierService);
    }

    @Test
    void twoValidMessagesAccumulateDistance() throws Exception {
        register("kafka-ac01");

        sendMessage("kafka-ac01", payload("kafka-ac01", FIRST_TIME, ATASEHIR));
        sendMessage("kafka-ac01", payload("kafka-ac01", SECOND_TIME, NOVADA));

        awaitInputs(2);
        double expected = new HaversineDistanceCalculatorStrategy().distanceMeters(ATASEHIR, NOVADA);
        assertEquals(expected, totalDistanceOf("kafka-ac01"), 0.01);
    }

    @Test
    void messageAtTheStoreRecordsOneVisit() throws Exception {
        register("kafka-ac02");

        sendMessage("kafka-ac02", payload("kafka-ac02", FIRST_TIME, ATASEHIR));

        awaitInputs(1);
        List<StoreVisit> entries = storeVisitRepository.findByCourierIdOrderByEntryTime("kafka-ac02");
        assertEquals(1, entries.size());
        assertEquals("Ataşehir MMM Migros", entries.getFirst().getStoreName());
        assertTrue(entries.getFirst().isOpen());
    }

    @Test
    void multipleMessagesInsideTheStoreRecordOneVisit() throws Exception {
        register("kafka-ac02");

        String firstTime = "2026-10-21T12:00:00Z";
        String secondTime = "2026-10-21T12:10:00Z";
        String thirdTime = "2026-10-21T12:20:00Z";

        sendMessage("kafka-ac02", payload("kafka-ac02", firstTime, ATASEHIR));
        sendMessage("kafka-ac02", payload("kafka-ac02", secondTime, ATASEHIR));
        sendMessage("kafka-ac02", payload("kafka-ac02", thirdTime, ATASEHIR));

        awaitInputs(3);
        List<StoreVisit> entries = storeVisitRepository.findByCourierIdOrderByEntryTime("kafka-ac02");
        assertEquals(1, entries.size());
        StoreVisit storeEntry = entries.getFirst();
        assertEquals("Ataşehir MMM Migros", storeEntry.getStoreName());
        assertEquals(firstTime, storeEntry.getEntryTime().toString());
        assertTrue(storeEntry.isOpen());
    }

    @Test
    void redeliveredMessageChangesNothing(CapturedOutput output) throws Exception {
        register("kafka-ac03");
        String message = payload("kafka-ac03", FIRST_TIME, ATASEHIR);

        sendMessage("kafka-ac03", message);
        awaitInputs(1);
        sendMessage("kafka-ac03", message);

        assertNothingSkippedFor("kafka-ac03", output);
        assertEquals(1, courierLocationRepository.count());
        assertEquals(0.0, totalDistanceOf("kafka-ac03"));
    }

    @Test
    void malformedPayloadIsSkippedAndTheStreamContinues(CapturedOutput output) throws Exception {
        register("kafka-ac04");

        sendMessage("kafka-ac04", "{not json");
        sendMessage("kafka-ac04", payload("kafka-ac04", FIRST_TIME, ATASEHIR));

        awaitInputs(1);
        String skipped = awaitSkipLogFor("kafka-ac04", output);
        assertThat(skipped).contains("exception=tools.jackson");
        assertFalse(output.getOut().contains("{not json"), "payload must not be logged");
        assertEquals(1, courierLocationRepository.count());
        assertEquals(0.0, totalDistanceOf("kafka-ac04"));
    }

    @Test
    void invalidLatitudeIsSkipped(CapturedOutput output) throws Exception {
        register("kafka-ac04");
        sendMessage("kafka-ac04", payload("kafka-ac04", FIRST_TIME, new GeoLocation(89.0, 29.1244229)).replace("89.0", "95"));

        String skipped = awaitSkipLogFor("kafka-ac04", output);
        assertThat(skipped).contains("ConstraintViolationException");
        assertEquals(0, courierLocationRepository.count());
    }

    @Test
    void unknownCourierIsSkippedWithoutRetry(CapturedOutput output) throws Exception {
        sendMessage("nobody", payload("nobody", FIRST_TIME, ATASEHIR));

        String skipped = awaitSkipLogFor("nobody", output);
        assertThat(skipped).contains("CourierNotFoundException");
        verify(courierService, times(1)).submitLocation(any());
        assertEquals(0, courierLocationRepository.count());
    }

    @Test
    void missingTimeIsSkipped(CapturedOutput output) throws Exception {
        register("kafka-ac04");

        sendMessage("kafka-ac04", "{\"courier\":\"kafka-ac04\",\"lat\":40.9923307,\"lng\":29.1244229}");

        String skipped = awaitSkipLogFor("kafka-ac04", output);
        assertThat(skipped).contains("ConstraintViolationException");
        assertEquals(0, courierLocationRepository.count());
    }

    @Test
    void transientFailureIsRetriedAndCommitted(CapturedOutput output) throws Exception {
        register("kafka-ac04");
        doThrow(new TransientDataAccessResourceException("db")).doCallRealMethod()
                .when(courierService).submitLocation(any());

        sendMessage("kafka-ac04", payload("kafka-ac04", FIRST_TIME, ATASEHIR));

        awaitInputs(1);
        verify(courierService, times(2)).submitLocation(any());
        assertNothingSkippedFor("kafka-ac04", output);
    }

    @Test
    void persistentFailureIsSkippedAfterThreeAttempts(CapturedOutput output) throws Exception {
        doThrow(new TransientDataAccessResourceException("db")).when(courierService).submitLocation(any());

        sendMessage("kafka-ac04", payload("kafka-ac04", FIRST_TIME, ATASEHIR));

        String skipped = awaitSkipLogFor("kafka-ac04", output);
        assertThat(skipped).contains("TransientDataAccessResourceException");
        verify(courierService, times(3)).submitLocation(any());
        assertEquals(0, courierLocationRepository.count());
    }

    @Test
    void interleavedCouriersKeepIndependentTotals() throws Exception {
        register("kafka-ac10-a");
        register("kafka-ac10-b");

        sendMessage("kafka-ac10-a", payload("kafka-ac10-a", FIRST_TIME, ATASEHIR));
        sendMessage("kafka-ac10-b", payload("kafka-ac10-b", FIRST_TIME, NOVADA));
        sendMessage("kafka-ac10-a", payload("kafka-ac10-a", SECOND_TIME, NOVADA));
        sendMessage("kafka-ac10-b", payload("kafka-ac10-b", SECOND_TIME, ATASEHIR));

        awaitInputs(4);
        double expected = new HaversineDistanceCalculatorStrategy().distanceMeters(ATASEHIR, NOVADA);
        assertEquals(expected, totalDistanceOf("kafka-ac10-a"), 0.01);
        assertEquals(expected, totalDistanceOf("kafka-ac10-b"), 0.01);
    }

    private void register(String courierId) {
        courierService.createCourier(courierId);
    }

    private void sendMessage(String key, String json) throws Exception {
        producer.send(TOPIC, key, json).get(10, TimeUnit.SECONDS);
    }

    private String payload(String courier, String time, GeoLocation location) {
        CourierLocationMessage message = new CourierLocationMessage(Instant.parse(time), courier, location.latitude(), location.longitude());
        return jsonMapper.writeValueAsString(message);
    }

    private void awaitInputs(long expected) {
        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() -> assertEquals(expected, courierLocationRepository.count()));
    }

    private double totalDistanceOf(String courierId) {
        Courier courier = courierRepository.findById(courierId).orElseThrow();
        return courier.getTotalDistanceMeters();
    }

    /**
     * Waits for the INFO line the error handler writes when it gives up on a record, identified by the record key.
     */
    private String awaitSkipLogFor(String key, CapturedOutput output) {
        String marker = "key=" + key + " ";
        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() -> assertTrue(output.getOut().contains(marker)));
        return output.getOut().lines()
                .filter(line -> line.contains("Skipped unprocessable location message") && line.contains(marker))
                .findFirst().orElseThrow();
    }

    private void assertNothingSkippedFor(String key, CapturedOutput output) {
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertFalse(output.getOut().contains("key=" + key + " ")));
    }
}
