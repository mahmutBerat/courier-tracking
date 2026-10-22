package com.mbi.couriertracking.store.application;

import com.mbi.couriertracking.TestcontainersConfiguration;
import com.mbi.couriertracking.courier.api.CourierLocationInput;
import com.mbi.couriertracking.courier.application.CourierService;
import com.mbi.couriertracking.courier.persistence.CourierLocationRepository;
import com.mbi.couriertracking.courier.persistence.CourierRepository;
import com.mbi.couriertracking.store.domain.StoreEntry;
import com.mbi.couriertracking.store.domain.StoreProximityState;
import com.mbi.couriertracking.store.persistence.StoreEntryRepository;
import com.mbi.couriertracking.store.persistence.StoreProximityStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Store entry detection through the HTTP endpoint against real PostgreSQL.
 * Positions are expressed as meters north of Ataşehir MMM Migros: 0 m is the store itself,
 * 50 m is inside the 100 m area, 150 m is outside.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StoreEntryIntegrationTests {

    private static final String ATASEHIR = "Ataşehir MMM Migros";
    private static final double ATASEHIR_LAT = 40.9923307;
    private static final double ATASEHIR_LNG = 29.1244229;
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final Instant NOON = Instant.parse("2026-10-21T12:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CourierService courierService;
    @Autowired
    private CourierRepository courierRepository;
    @Autowired
    private CourierLocationRepository inputRepository;
    @Autowired
    private StoreProximityStateRepository proximityStateRepository;
    @Autowired
    private StoreEntryRepository storeEntryRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearState() {
        inputRepository.deleteAllInBatch();
        courierRepository.deleteAllInBatch(); // cascades to proximity state and entries
        courierService.createCourier("courier-1");
    }

    @Test
    void firstReportAtTheStoreCountsOneEntryForThatStoreOnly() throws Exception {
        postMetersNorthOfAtasehir("courier-1", NOON, 0);

        List<StoreEntry> entries = storeEntryRepository.findByCourierIdOrderByEntryTime("courier-1");
        assertThat(entries).singleElement().satisfies(entry -> {
            assertEquals(ATASEHIR, entry.getStoreName());
            assertEquals(NOON, entry.getEntryTime());
            assertTrue(entry.isCounted());
        });
        // No pair row for the four stores the courier has never entered.
        assertThat(proximityStateRepository.findAll()).singleElement()
                .extracting(StoreProximityState::getStoreName).isEqualTo(ATASEHIR);
    }

    @Test
    void reentryWithinOneMinuteIsRecordedButNotCounted() throws Exception {
        postMetersNorthOfAtasehir("courier-1", NOON, 50);                  // enter -> counted
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(30), 150); // leave
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(59), 50);  // re-enter after 59 s -> not counted
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(90), 150); // leave
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(119), 50); // re-enter 60 s after 12:00:59 -> counted

        List<StoreEntry> entries = storeEntryRepository.findByCourierIdOrderByEntryTime("courier-1");
        assertThat(entries).extracting(StoreEntry::isCounted).containsExactly(true, false, true);
        assertThat(entries).extracting(StoreEntry::getEntryTime)
                .containsExactly(NOON, NOON.plusSeconds(59), NOON.plusSeconds(119));
        StoreProximityState state = proximityStateRepository.findByCourierIdAndStoreName("courier-1", ATASEHIR).orElseThrow();
        assertTrue(state.isInside());
        assertEquals(NOON.plusSeconds(119), state.getLastPhysicalEntryTime());
    }

    @Test
    void staleAndDuplicateReportsDoNotCreateEntries() throws Exception {
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(10), 150); // far away, processed first
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(5), 0);    // older -> stored, not evaluated
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(20), 0);   // enter -> counted
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(20), 0);   // exact duplicate -> nothing

        assertEquals(3, inputRepository.count());
        assertThat(storeEntryRepository.findByCourierIdOrderByEntryTime("courier-1"))
                .extracting(StoreEntry::getEntryTime).containsExactly(NOON.plusSeconds(20));
    }

    @Test
    void failedEntryInsertRollsBackInputAndTravelState() throws Exception {
        // A failing CHECK on store_entries makes the last write of the transaction fail.
        jdbcTemplate.execute("ALTER TABLE store_entries ADD CONSTRAINT test_reject_entries CHECK (false)");
        try {
            assertThrows(RuntimeException.class,
                    () -> courierService.submitLocation(atasehirInput("courier-1", NOON, 0)));

            assertEquals(0, inputRepository.count());
            assertNull(courierRepository.findById("courier-1").orElseThrow().getLastProcessedTime());
            assertEquals(0, proximityStateRepository.count());
        } finally {
            jdbcTemplate.execute("ALTER TABLE store_entries DROP CONSTRAINT test_reject_entries");
        }

        // The same report is accepted once the failure is gone.
        courierService.submitLocation(atasehirInput("courier-1", NOON, 0));
        assertEquals(1, storeEntryRepository.count());
    }

    @Test
    void couriersHaveIndependentEntries() throws Exception {
        courierService.createCourier("courier-2");

        postMetersNorthOfAtasehir("courier-1", NOON, 0);
        postMetersNorthOfAtasehir("courier-2", NOON.plusSeconds(10), 0);

        assertThat(storeEntryRepository.findByCourierIdOrderByEntryTime("courier-1"))
                .extracting(StoreEntry::isCounted).containsExactly(true);
        assertThat(storeEntryRepository.findByCourierIdOrderByEntryTime("courier-2"))
                .extracting(StoreEntry::isCounted).containsExactly(true);
    }

    @Test
    void concurrentReportsInsideTheStoreProduceExactlyOneEntry() throws Exception {
        // Eight reports inside the area with distinct times, submitted at the same moment.
        // Whichever is processed first enters; later times stay inside, earlier times are stale.
        int workers = 8;
        var startBarrier = new CyclicBarrier(workers);
        var tasks = new ArrayList<Callable<Void>>();
        for (int second = 0; second < workers; second++) {
            CourierLocationInput report = atasehirInput("courier-1", NOON.plusSeconds(second), 10 * second);
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

        assertThat(storeEntryRepository.findAll()).singleElement().satisfies(entry -> {
            assertEquals(ATASEHIR, entry.getStoreName());
            assertTrue(entry.isCounted());
        });
        assertTrue(proximityStateRepository.findByCourierIdAndStoreName("courier-1", ATASEHIR).orElseThrow().isInside());
        assertEquals(workers, inputRepository.count());
    }

    private void postMetersNorthOfAtasehir(String courier, Instant time, double metersNorth) throws Exception {
        CourierLocationInput input = atasehirInput(courier, time, metersNorth);
        mvc.perform(post("/api/v1/couriers/locations").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"time":"%s","courier":"%s","lat":%s,"lng":%s}
                                """.formatted(input.time(), input.courier(), input.lat(), input.lng())))
                .andExpect(status().isNoContent());
    }

    private static CourierLocationInput atasehirInput(String courier, Instant time, double metersNorth) {
        double latitude = ATASEHIR_LAT + Math.toDegrees(metersNorth / EARTH_RADIUS_METERS);
        return new CourierLocationInput(time, courier, latitude, ATASEHIR_LNG);
    }
}
