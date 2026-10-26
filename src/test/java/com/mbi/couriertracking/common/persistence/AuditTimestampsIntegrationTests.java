package com.mbi.couriertracking.common.persistence;

import com.mbi.couriertracking.TestcontainersConfiguration;
import com.mbi.couriertracking.courier.application.CourierService;
import com.mbi.couriertracking.courier.domain.Courier;
import com.mbi.couriertracking.courier.persistence.CourierLocationRecord;
import com.mbi.couriertracking.courier.persistence.CourierLocationRepository;
import com.mbi.couriertracking.courier.persistence.CourierRepository;
import com.mbi.couriertracking.store.domain.StoreVisit;
import com.mbi.couriertracking.store.persistence.StoreVisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every table records when a row was inserted and last written, whether the row was created by
 * JPA (store visits) or by a native insert (couriers, location inputs).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditTimestampsIntegrationTests {

    private static final double ATASEHIR_LAT = 40.9923307;
    private static final double ATASEHIR_LNG = 29.1244229;
    private static final Instant NOON = Instant.parse("2026-10-21T12:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CourierService courierService;
    @Autowired
    private CourierRepository couriers;
    @Autowired
    private CourierLocationRepository inputs;
    @Autowired
    private StoreVisitRepository storeVisits;

    @BeforeEach
    void clearState() {
        inputs.deleteAllInBatch();
        couriers.deleteAllInBatch(); // cascades to store visits
    }

    @Test
    void nativelyInsertedRowsAreStampedByTheDatabaseDefault() throws Exception {
        courierService.createCourier("courier-1");
        postLocation("courier-1", NOON, 0, 0);

        Courier courier = couriers.findById("courier-1").orElseThrow();
        assertNotNull(courier.getCreatedAt());
        CourierLocationRecord input = inputs.findAll().getFirst();
        assertNotNull(input.getCreatedAt());
        assertEquals(input.getCreatedAt(), input.getUpdatedAt());
    }

    @Test
    void updatingACourierMovesUpdatedAtAndKeepsCreatedAt() throws Exception {
        courierService.createCourier("courier-1");
        Instant createdAt = couriers.findById("courier-1").orElseThrow().getCreatedAt();

        postLocation("courier-1", NOON, 0, 0);

        Courier courier = couriers.findById("courier-1").orElseThrow();
        assertEquals(createdAt, courier.getCreatedAt());
        assertFalse(courier.getUpdatedAt().isBefore(createdAt));
    }

    @Test
    void visitsAreStampedOnInsertAndOnClose() throws Exception {
        courierService.createCourier("courier-1");
        postLocation("courier-1", NOON, ATASEHIR_LAT, ATASEHIR_LNG);
        StoreVisit opened = storeVisits.findAll().getFirst();
        assertNotNull(opened.getCreatedAt());
        assertEquals(opened.getCreatedAt(), opened.getUpdatedAt());

        postLocation("courier-1", NOON.plusSeconds(30), 0, 0);

        StoreVisit closed = storeVisits.findAll().getFirst();
        assertFalse(closed.isOpen());
        assertEquals(opened.getCreatedAt(), closed.getCreatedAt());
        assertThat(closed.getUpdatedAt()).isAfter(closed.getCreatedAt());
    }

    private void postLocation(String courier, Instant time, double lat, double lng) throws Exception {
        mvc.perform(post("/api/v1/couriers/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"time":"%s","courier":"%s","lat":%s,"lng":%s}
                                """.formatted(time, courier, lat, lng)))
                .andExpect(status().isNoContent());
    }
}
