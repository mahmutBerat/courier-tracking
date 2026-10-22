package com.mbi.couriertracking.courier.api;

import com.mbi.couriertracking.courier.domain.Courier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import com.mbi.couriertracking.TestcontainersConfiguration;
import com.mbi.couriertracking.courier.persistence.CourierRepository;
import com.mbi.couriertracking.courier.persistence.CourierLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CourierRegistrationIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private CourierRepository couriers;
    @Autowired private CourierLocationRepository inputs;

    @BeforeEach
    void clearState() {
        inputs.deleteAllInBatch();
        couriers.deleteAllInBatch();
    }

    @Test
    void emptyRegistryReturnsEmptyArray() throws Exception {
        mvc.perform(get("/api/v1/couriers")).andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void creationInitializesStateAndListsOnlyIdsInOrder() throws Exception {
        create("b", 201);
        create("a", 201);
        assertThat(couriers.findAll()).allSatisfy(courier -> {
            assertThat(courier.getTotalDistanceMeters()).isZero();
            assertThat(courier.getLastLatitude()).isNull();
            assertThat(courier.getLastLongitude()).isNull();
            assertThat(courier.getLastProcessedTime()).isNull();
        });
        mvc.perform(get("/api/v1/couriers")).andExpect(status().isOk())
                .andExpect(content().string("[{\"courierId\":\"a\"},{\"courierId\":\"b\"}]"));
        assertThat(inputs.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"courierId\":null}", "{\"courierId\":\"\"}", "{\"courierId\":\" \"}"})
    void invalidRegistrationCreatesNothing(String body) throws Exception {
        mvc.perform(post("/api/v1/couriers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(couriers.count()).isZero();
    }

    @Test
    void unknownLocationIsRejectedAndCanBeRetriedAfterRegistration() throws Exception {
        location(0, 0, 404);
        assertThat(inputs.count()).isZero();
        assertThat(couriers.count()).isZero();
        create("courier-1", 201);
        location(0, 0, 204);
        location(1, 1, 204);
        Courier before = couriers.findById("courier-1").orElseThrow();
        create("courier-1", 409);
        Courier after = couriers.findById("courier-1").orElseThrow();
        assertThat(after.getTotalDistanceMeters()).isPositive().isEqualTo(before.getTotalDistanceMeters());
        assertThat(after.getLastProcessedTime()).isEqualTo(before.getLastProcessedTime());
        assertThat(after.getLastLongitude()).isEqualTo(before.getLastLongitude());
        assertThat(couriers.count()).isEqualTo(1);
        assertThat(inputs.count()).isEqualTo(2);
    }

    @Test
    void concurrentRegistrationCreatesExactlyOneCourier() throws Exception {
        int workers = 8;
        var start = new CyclicBarrier(workers);
        var tasks = new ArrayList<Callable<Integer>>();
        for (int i = 0; i < workers; i++) {
            tasks.add(() -> {
                start.await(10, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/couriers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courierId\":\"courier-1\"}"))
                        .andReturn().getResponse().getStatus();
            });
        }
        var statuses = new ArrayList<Integer>();
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            for (Future<Integer> result : executor.invokeAll(tasks, 20, TimeUnit.SECONDS)) {
                statuses.add(result.get(1, TimeUnit.SECONDS));
            }
        }
        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1);
        assertThat(couriers.count()).isEqualTo(1);
    }

    private void create(String id, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/couriers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courierId\":\"%s\"}".formatted(id)))
                .andExpect(status().is(expectedStatus));
    }

    private void location(int seconds, int longitude, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/couriers/locations").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"time":"2026-10-21T12:00:0%sZ","courier":"courier-1","lat":0,"lng":%s}
                                """.formatted(seconds, longitude)))
                .andExpect(status().is(expectedStatus));
    }
}
