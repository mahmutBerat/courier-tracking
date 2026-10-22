package com.mbi.couriertracking.courier.api;

import com.mbi.couriertracking.TestcontainersConfiguration;
import com.mbi.couriertracking.courier.application.CourierService;
import com.mbi.couriertracking.courier.domain.Courier;
import com.mbi.couriertracking.courier.persistence.CourierLocationRepository;
import com.mbi.couriertracking.courier.persistence.CourierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Travel distance behavior against real PostgreSQL.
 * Every location sits on the equator (lat 0), so moving one degree of longitude is exactly
 * {@link #ONE_DEGREE_METERS}; expected totals are multiples of that value.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TravelDistanceIntegrationTests {

    /**
     * Haversine distance for one degree of longitude on the equator: 6_371_000 m * PI / 180.
     */
    private static final double ONE_DEGREE_METERS = 111_194.926644559;
    private static final double TOLERANCE = 0.000001;
    private static final Instant START = Instant.parse("2026-10-21T12:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CourierService courierService;
    @Autowired
    private CourierRepository courierRepository;
    @Autowired
    private CourierLocationRepository inputRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void clearState() {
        inputRepository.deleteAllInBatch();
        courierRepository.deleteAllInBatch();
        courierService.createCourier("courier-1");
    }

    @Test
    void accumulatesOnlyStrictlyNewerReportsAcrossRequests() throws Exception {
        // First report: initializes state, contributes nothing.
        postLocation("courier-1", START, 0);
        assertEquals(0.0, courier("courier-1").getTotalDistanceMeters());

        postLocation("courier-1", START.plusSeconds(2), 1);  // +1 degree -> counted
        postLocation("courier-1", START.plusSeconds(1), 50); // older than last processed -> stored, ignored
        postLocation("courier-1", START.plusSeconds(2), 60); // same time as last processed -> stored, ignored
        postLocation("courier-1", START.plusSeconds(3), 2);  // +1 degree from lng 1 (not from the ignored 60)
        postLocation("courier-1", START.plusSeconds(3), 2);  // exact duplicate -> not stored

        Courier courier = courier("courier-1");
        assertEquals(2 * ONE_DEGREE_METERS, courier.getTotalDistanceMeters(), TOLERANCE);
        assertEquals(START.plusSeconds(3), courier.getLastProcessedTime());
        assertEquals(2.0, courier.getLastLongitude());
        assertEquals(5, inputRepository.count()); // five distinct reports, one duplicate skipped

        mvc.perform(get("/api/v1/couriers/distance").param("courierId", "courier-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courierId").value("courier-1"))
                .andExpect(jsonPath("$.distanceMeters").value(closeTo(2 * ONE_DEGREE_METERS, TOLERANCE)));
    }

    @Test
    void registeredCourierWithoutLocationsReturnsZero() throws Exception {
        mvc.perform(get("/api/v1/couriers/distance").param("courierId", "courier-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courierId").value("courier-1"))
                .andExpect(jsonPath("$.distanceMeters").value(0));

        assertNull(courier("courier-1").getLastProcessedTime());
    }

    @Test
    void unknownCourierQueryReturnsNotFoundWithoutCreatingState() throws Exception {
        mvc.perform(get("/api/v1/couriers/distance").param("courierId", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertFalse(courierRepository.existsById("missing"));
    }

    @Test
    void distanceOfOneCourierDoesNotAffectAnother() throws Exception {
        courierService.createCourier("courier-2");

        postLocation("courier-1", START, 0);
        postLocation("courier-1", START.plusSeconds(1), 1);

        assertEquals(ONE_DEGREE_METERS, courier("courier-1").getTotalDistanceMeters(), TOLERANCE);
        assertEquals(0.0, courier("courier-2").getTotalDistanceMeters());
    }

    @Test
    void sameMillisecondIsStoredButDoesNotAdvanceTravelState() throws Exception {
        postLocation("courier-1", START.plusMillis(123), 0);
        postLocation("courier-1", START.plusNanos(123_999_999), 1); // truncates to .123 -> same time, ignored
        postLocation("courier-1", START.plusMillis(124), 0);        // newer, same place -> +0, time advances

        Courier courier = courier("courier-1");
        assertEquals(0.0, courier.getTotalDistanceMeters());
        assertEquals(START.plusMillis(124), courier.getLastProcessedTime());
        assertEquals(0.0, courier.getLastLongitude());
        assertEquals(3, inputRepository.count()); // all three are distinct reports
    }

    @Test
    void failedCourierUpdateRollsBackInputRowAndAllowsRetry() throws Exception {
        postLocation("courier-1", START, 0);
        CourierLocationInput next = input("courier-1", 1, 1);

        // Temporary CHECK makes the courier UPDATE fail at commit, after the input row was inserted.
        jdbcTemplate.execute("ALTER TABLE couriers ADD CONSTRAINT test_zero_total CHECK (total_distance_meters = 0)");
        try {
            assertThrows(RuntimeException.class, () -> courierService.submitLocation(next));

            // Both writes rolled back: input count unchanged, courier state unchanged.
            assertEquals(1, inputRepository.count());
            Courier courier = courier("courier-1");
            assertEquals(0.0, courier.getTotalDistanceMeters());
            assertEquals(START, courier.getLastProcessedTime());
        } finally {
            jdbcTemplate.execute("ALTER TABLE couriers DROP CONSTRAINT test_zero_total");
        }

        // The same report is not treated as a duplicate, because its row was rolled back.
        courierService.submitLocation(next);
        assertEquals(2, inputRepository.count());
        assertEquals(ONE_DEGREE_METERS, courier("courier-1").getTotalDistanceMeters(), TOLERANCE);
    }

    @Test
    void sameCourierUpdatesAreSerializedWhileOtherCouriersProceed() throws Exception {
        courierService.createCourier("courier-2");
        courierService.submitLocation(input("courier-1", 0, 0));

        var firstCommittedLockHeld = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            // First writer: processes (t+1, lng 1) and keeps its transaction open, holding the courier-1 row lock.
            Future<?> first = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                courierService.submitLocation(input("courier-1", 1, 1));
                firstCommittedLockHeld.countDown();
                await(releaseFirst, 10);
            }));
            try {
                await(firstCommittedLockHeld, 5);

                // Second writer for the same courier must block on SELECT ... FOR UPDATE.
                Future<?> second = executor.submit(() -> courierService.submitLocation(input("courier-1", 2, 2)));
                awaitCourierLockWait();
                assertFalse(second.isDone());

                // A different courier is not blocked by courier-1's lock.
                courierService.submitLocation(input("courier-2", 0, 40));
                assertEquals(0.0, courier("courier-2").getTotalDistanceMeters());

                releaseFirst.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
            }
        }

        // The second writer saw the first writer's committed state: 0 -> 1 -> 2 degrees.
        Courier courier = courier("courier-1");
        assertEquals(2 * ONE_DEGREE_METERS, courier.getTotalDistanceMeters(), TOLERANCE);
        assertEquals(START.plusSeconds(2), courier.getLastProcessedTime());
        assertEquals(2.0, courier.getLastLongitude());
        assertEquals(4, inputRepository.count()); // three for courier-1, one for courier-2
    }

    @Test
    void concurrentFirstReportsInitializeOnceAndKeepAllDistinctInputs() throws Exception {
        // Eight reports with the same time but different longitudes, submitted at the same moment.
        int workers = 8;
        var startBarrier = new CyclicBarrier(workers);
        var tasks = new ArrayList<Callable<Void>>();
        for (int longitude = 0; longitude < workers; longitude++) {
            CourierLocationInput report = input("courier-1", 0, longitude);
            tasks.add(() -> {
                startBarrier.await(10, TimeUnit.SECONDS);
                courierService.submitLocation(report);
                return null;
            });
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            for (Future<Void> result : executor.invokeAll(tasks, 20, TimeUnit.SECONDS)) {
                result.get(1, TimeUnit.SECONDS);
            }
        }

        // Whichever report took the lock first initialized the state; the rest share its time and are ignored.
        Courier courier = courier("courier-1");
        assertEquals(0.0, courier.getTotalDistanceMeters());
        assertEquals(START, courier.getLastProcessedTime());
        assertThat(courier.getLastLongitude()).isBetween(0.0, 7.0);
        assertEquals(workers, inputRepository.count()); // all distinct reports are stored
    }

    private Courier courier(String courierId) {
        return courierRepository.findById(courierId).orElseThrow();
    }

    private static CourierLocationInput input(String courier, int secondsAfterStart, double longitude) {
        return new CourierLocationInput(START.plusSeconds(secondsAfterStart), courier, 0.0, longitude);
    }

    private void postLocation(String courier, Instant time, double longitude) throws Exception {
        mvc.perform(post("/api/v1/couriers/locations").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"time":"%s","courier":"%s","lat":0,"lng":%s}
                                """.formatted(time, courier, longitude)))
                .andExpect(status().isNoContent());
    }

    /**
     * Polls pg_stat_activity until a session is waiting on a lock in a query touching couriers.
     */
    private void awaitCourierLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                        AND query ILIKE '%couriers%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected an overlapping database lock wait");
    }

    private static void await(CountDownLatch latch, int seconds) {
        try {
            if (!latch.await(seconds, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
