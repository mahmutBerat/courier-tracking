package com.mbi.couriertracking.store.application;

import com.mbi.couriertracking.TestcontainersConfiguration;
import com.mbi.couriertracking.courier.api.CourierLocationInput;
import com.mbi.couriertracking.courier.application.CourierService;
import com.mbi.couriertracking.courier.domain.Courier;
import com.mbi.couriertracking.courier.domain.StoreStatus;
import com.mbi.couriertracking.courier.persistence.CourierLocationRepository;
import com.mbi.couriertracking.courier.persistence.CourierRepository;
import com.mbi.couriertracking.store.domain.StoreVisit;
import com.mbi.couriertracking.store.persistence.StoreVisitRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Store visit detection and the courier store summary through the HTTP endpoint against real PostgreSQL.
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
    private static final String NOVADA = "Novada MMM Migros";
    private static final double NOVADA_LAT = 40.986106;
    private static final double NOVADA_LNG = 29.1161293;
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
    private StoreVisitRepository storeVisits;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void clearState() {
        inputRepository.deleteAllInBatch();
        courierRepository.deleteAllInBatch(); // cascades to store visits
        courierService.createCourier("courier-1");
    }

    @Test
    void firstReportAtTheStoreOpensOneVisitForThatStoreOnly() throws Exception {
        postMetersNorthOfAtasehir("courier-1", NOON, 0);

        assertThat(storeVisits.findAll()).singleElement().satisfies(visit -> {
            assertEquals(ATASEHIR, visit.getStoreName());
            assertEquals(NOON, visit.getEntryTime());
            assertTrue(visit.isOpen());
        });
        Courier courier = courier("courier-1");
        assertEquals(ATASEHIR, courier.getCurrentStoreName());
        assertEquals(ATASEHIR, courier.getLastStoreName());
        assertEquals(NOON, courier.getLastStoreEntryTime());
        assertEquals(StoreStatus.IN_STORE, courier.getStoreStatus());
    }

    @Test
    void leavingClosesTheVisitAndKeepsTheLastStore() throws Exception {
        postMetersNorthOfAtasehir("courier-1", NOON, 0);
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(30), 150);

        assertThat(storeVisits.findAll()).singleElement().satisfies(visit -> {
            assertEquals(NOON, visit.getEntryTime());
            assertEquals(NOON.plusSeconds(30), visit.getExitTime());
            assertFalse(visit.isOpen());
        });
        Courier courier = courier("courier-1");
        assertNull(courier.getCurrentStoreName());
        assertEquals(ATASEHIR, courier.getLastStoreName());
        assertEquals(NOON, courier.getLastStoreEntryTime());
        assertEquals(StoreStatus.OUT_STORE, courier.getStoreStatus());
    }

    @Test
    void reentryWithinOneMinuteResumesTheVisitInsteadOfAddingARow() throws Exception {
        postMetersNorthOfAtasehir("courier-1", NOON, 50);                  // enter -> visit 1
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(30), 150); // leave
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(59), 50);  // back after 59 s -> visit 1 resumes, no row
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(90), 150); // leave
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(119), 50); // 119 s after the entry -> visit 2

        List<StoreVisit> visits = storeVisits.findByCourierIdOrderByEntryTime("courier-1");
        assertThat(visits).extracting(StoreVisit::getEntryTime).containsExactly(NOON, NOON.plusSeconds(119));
        assertThat(visits).extracting(StoreVisit::getExitTime).containsExactly(NOON.plusSeconds(90), null);
        assertEquals(NOON.plusSeconds(119), courier("courier-1").getLastStoreEntryTime());
    }

    @Test
    void reentryTwentySecondsAfterTheEntryKeepsOneRow() throws Exception {
        // Reported case: c1 at Novada 12:18:35 and again 12:18:55 produced two rows.
        Instant entry = Instant.parse("2026-10-21T12:18:35Z");
        double outsideNovadaLat = NOVADA_LAT + Math.toDegrees(150 / EARTH_RADIUS_METERS);
        postLocation("courier-1", entry, NOVADA_LAT, NOVADA_LNG);
        postLocation("courier-1", entry.plusSeconds(10), outsideNovadaLat, NOVADA_LNG);
        postLocation("courier-1", entry.plusSeconds(20), NOVADA_LAT, NOVADA_LNG);

        assertThat(storeVisits.findByCourierIdOrderByEntryTime("courier-1")).singleElement().satisfies(visit -> {
            assertEquals(NOVADA, visit.getStoreName());
            assertEquals(entry, visit.getEntryTime());
            assertTrue(visit.isOpen());
        });
        Courier courier = courier("courier-1");
        assertEquals(NOVADA, courier.getCurrentStoreName());
        assertEquals(entry, courier.getLastStoreEntryTime());
    }

    @Test
    void movingBetweenStoresClosesTheLeftVisitAndOpensTheNewOne() throws Exception {
        // Novada follows Ataşehir in the catalog, so its exit must be observed although Ataşehir is entered.
        postLocation("courier-1", NOON, NOVADA_LAT, NOVADA_LNG);                      // enter Novada
        postLocation("courier-1", NOON.plusSeconds(120), ATASEHIR_LAT, ATASEHIR_LNG); // move to Ataşehir
        postLocation("courier-1", NOON.plusSeconds(240), NOVADA_LAT, NOVADA_LNG);     // back to Novada

        List<StoreVisit> visits = storeVisits.findByCourierIdOrderByEntryTime("courier-1");
        assertThat(visits).extracting(StoreVisit::getStoreName).containsExactly(NOVADA, ATASEHIR, NOVADA);
        assertThat(visits).extracting(StoreVisit::getExitTime)
                .containsExactly(NOON.plusSeconds(120), NOON.plusSeconds(240), null);
        Courier courier = courier("courier-1");
        assertEquals(NOVADA, courier.getCurrentStoreName());
        assertEquals(NOON.plusSeconds(240), courier.getLastStoreEntryTime());
    }

    @Test
    void staleAndDuplicateReportsDoNotCreateVisits() throws Exception {
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(10), 150); // far away, processed first
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(5), 0);    // older -> stored, not evaluated
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(20), 0);   // enter -> counted
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(20), 0);   // exact duplicate -> nothing
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(15), 150); // older than the entry -> no exit

        assertEquals(4, inputRepository.count());
        assertThat(storeVisits.findByCourierIdOrderByEntryTime("courier-1")).singleElement().satisfies(visit -> {
            assertEquals(NOON.plusSeconds(20), visit.getEntryTime());
            assertTrue(visit.isOpen());
        });
        assertEquals(StoreStatus.IN_STORE, courier("courier-1").getStoreStatus());
    }

    @Test
    void failedVisitInsertRollsBackInputTravelStateAndSummary() throws Exception {
        // A failing CHECK on courier_store_visits makes the last write of the transaction fail.
        jdbcTemplate.execute("ALTER TABLE courier_store_visits ADD CONSTRAINT test_reject_visits CHECK (false)");
        try {
            assertThrows(RuntimeException.class,
                    () -> courierService.submitLocation(atasehirInput("courier-1", NOON, 0)));

            assertEquals(0, inputRepository.count());
            Courier courier = courier("courier-1");
            assertNull(courier.getLastProcessedTime());
            assertNull(courier.getLastStoreName());
            assertNull(courier.getCurrentStoreName());
            assertEquals(0, storeVisits.count());
        } finally {
            jdbcTemplate.execute("ALTER TABLE courier_store_visits DROP CONSTRAINT test_reject_visits");
        }

        // The same report is accepted once the failure is gone.
        courierService.submitLocation(atasehirInput("courier-1", NOON, 0));
        assertEquals(1, storeVisits.count());
        assertEquals(ATASEHIR, courier("courier-1").getCurrentStoreName());
    }

    @Test
    void couriersHaveIndependentVisits() throws Exception {
        courierService.createCourier("courier-2");

        postMetersNorthOfAtasehir("courier-1", NOON, 0);
        postMetersNorthOfAtasehir("courier-2", NOON.plusSeconds(10), 0);

        assertThat(storeVisits.findByCourierIdOrderByEntryTime("courier-1"))
                .extracting(StoreVisit::getStoreName).containsExactly(ATASEHIR);
        assertThat(storeVisits.findByCourierIdOrderByEntryTime("courier-2"))
                .extracting(StoreVisit::getStoreName).containsExactly(ATASEHIR);
        assertEquals(NOON, courier("courier-1").getLastStoreEntryTime());
        assertEquals(NOON.plusSeconds(10), courier("courier-2").getLastStoreEntryTime());
    }

    @Test
    void concurrentReportsInsideTheStoreProduceExactlyOneVisit() throws Exception {
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

        assertThat(storeVisits.findAll()).singleElement().satisfies(visit -> {
            assertEquals(ATASEHIR, visit.getStoreName());
            assertTrue(visit.isOpen());
        });
        assertEquals(ATASEHIR, courier("courier-1").getCurrentStoreName());
        assertEquals(workers, inputRepository.count());
    }

    @Test
    void distanceQueryKeepsLastEnteredStoreAfterExitAndIgnoresStaleReports() throws Exception {
        assertDistanceStatus(null, null, "out-store", false);
        postMetersNorthOfAtasehir("courier-1", NOON, 150);
        assertDistanceStatus(null, null, "out-store", false);
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(1), 0);
        assertDistanceStatus(ATASEHIR, NOON.plusSeconds(1), "in-store", true);
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(2), 150);
        assertDistanceStatus(ATASEHIR, NOON.plusSeconds(1), "out-store", false);
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(3), 0); // back within a minute: the visit resumes, no entrance
        assertDistanceStatus(ATASEHIR, NOON.plusSeconds(1), "in-store", false);
        postLocation("courier-1", NOON.plusSeconds(4), NOVADA_LAT, NOVADA_LNG);
        assertDistanceStatus(NOVADA, NOON.plusSeconds(4), "in-store", true);
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(3), 0); // duplicate
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(2), 50); // stale
        assertDistanceStatus(NOVADA, NOON.plusSeconds(4), "in-store", true); // unchanged: nothing was processed
        postMetersNorthOfAtasehir("courier-1", NOON.plusSeconds(5), 150);
        assertDistanceStatus(NOVADA, NOON.plusSeconds(4), "out-store", false);
    }

    @Test
    void secondOpenVisitForOnePairIsRejectedByTheDatabase() throws Exception {
        postMetersNorthOfAtasehir("courier-1", NOON, 0);

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO courier_store_visits (courier_id, store_name, entry_time) VALUES (?, ?, ?)",
                "courier-1", ATASEHIR, Timestamp.from(NOON.plusSeconds(5))));
    }

    @Test
    void processedReportUpdatesTheCourierRowOnce() throws Exception {
        // Reading the visits must not flush the courier early; travel state and store summary go out in one UPDATE.
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            postMetersNorthOfAtasehir("courier-1", NOON, 0);

            assertEquals(1, statistics.getEntityUpdateCount(), "UPDATE couriers statements for one processed report");
            assertEquals(1, statistics.getEntityInsertCount(), "the opened visit");
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void oldStoreTablesAreGone() {
        assertNull(jdbcTemplate.queryForObject("SELECT to_regclass('store_entries')", String.class));
        assertNull(jdbcTemplate.queryForObject("SELECT to_regclass('courier_store_proximity')", String.class));
        assertEquals("courier_store_visits", jdbcTemplate.queryForObject("SELECT to_regclass('courier_store_visits')", String.class));
    }

    private void assertDistanceStatus(String storeName, Instant entryTime, String currentStatus, boolean entry) throws Exception {
        mvc.perform(get("/api/v1/couriers/distance").param("courierId", "courier-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courierId").value("courier-1"))
                .andExpect(jsonPath("$.distanceMeters").isNumber())
                .andExpect(jsonPath("$.lastStoreName").value(storeName == null ? nullValue() : is(storeName)))
                .andExpect(jsonPath("$.lastStoreEntryTime").value(entryTime == null ? nullValue() : is(entryTime.toString())))
                .andExpect(jsonPath("$.currentStatus").value(currentStatus))
                .andExpect(jsonPath("$.isEntry").value(entry));
    }

    private Courier courier(String courierId) {
        return courierRepository.findById(courierId).orElseThrow();
    }

    private void postMetersNorthOfAtasehir(String courier, Instant time, double metersNorth) throws Exception {
        CourierLocationInput input = atasehirInput(courier, time, metersNorth);
        postLocation(courier, time, input.lat(), input.lng());
    }

    private void postLocation(String courier, Instant time, double lat, double lng) throws Exception {
        mvc.perform(post("/api/v1/couriers/locations").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"time":"%s","courier":"%s","lat":%s,"lng":%s}
                                """.formatted(time, courier, lat, lng)))
                .andExpect(status().isNoContent());
    }

    private static CourierLocationInput atasehirInput(String courier, Instant time, double metersNorth) {
        double latitude = ATASEHIR_LAT + Math.toDegrees(metersNorth / EARTH_RADIUS_METERS);
        return new CourierLocationInput(time, courier, latitude, ATASEHIR_LNG);
    }
}
