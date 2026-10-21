package com.mbi.couriertracking.courier.api;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import com.mbi.couriertracking.TestcontainersConfiguration;
import com.mbi.couriertracking.courier.persistence.CourierLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LocationSubmissionIntegrationTests {

    private static final String INPUT = """
            {"time":"2026-10-21T15:00:00.123456+03:00","courier":"courier-1",
             "lat":40.9923307,"lng":29.1244229}
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CourierLocationRepository repository;

    @Autowired
    private com.mbi.couriertracking.courier.persistence.CourierRepository courierRepository;

    @BeforeEach
    void clearInputs() {
        repository.deleteAllInBatch();
        courierRepository.deleteAllInBatch();
        courierRepository.saveAndFlush(new com.mbi.couriertracking.courier.domain.Courier("courier-1"));
        courierRepository.saveAndFlush(new com.mbi.couriertracking.courier.domain.Courier("courier-2"));
    }

    @Test
    void commitsCompleteInputBeforeReturningNoContent() throws Exception {
        mvc.perform(post("/api/v1/couriers/locations")
                        .contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(repository.findAll()).singleElement().satisfies(record -> {
            assertNotNull(record.getId());
            assertEquals(Instant.parse("2026-10-21T12:00:00.123Z"), record.getTime());
            assertEquals("courier-1", record.getCourierId());
            assertEquals(40.9923307, record.getLatitude());
            assertEquals(29.1244229, record.getLongitude());
        });

        mvc.perform(get("/api/v1/couriers/distance").param("courierId", "courier-1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.distanceMeters").value(0));
    }

    @Test
    void repeatedSubmissionsCreateOnlyOneRecord() throws Exception {
        for (int submission = 0; submission < 2; submission++) {
            mvc.perform(post("/api/v1/couriers/locations")
                            .contentType(MediaType.APPLICATION_JSON).content(INPUT))
                    .andExpect(status().isNoContent());
        }

        assertEquals(1L, repository.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"courier", "time", "latitude", "longitude"})
    void changingAnyIdentityFieldCreatesAnotherRecord(String field) throws Exception {
        String different = switch (field) {
            case "courier" -> INPUT.replace("courier-1", "courier-2");
            case "time" -> INPUT.replace("15:00:00", "15:00:01");
            case "latitude" -> INPUT.replace("40.9923307", "40.9923308");
            case "longitude" -> INPUT.replace("29.1244229", "29.1244230");
            default -> throw new IllegalArgumentException(field);
        };
        submit(INPUT);
        submit(different);
        assertEquals(2L, repository.count());
    }

    @Test
    void equivalentUtcInstantsAreDuplicates() throws Exception {
        submit(INPUT);
        submit(INPUT.replace("15:00:00.123456+03:00", "12:00:00.123456Z"));
        assertEquals(1L, repository.count());
    }

    @Test
    void subMillisecondDifferencesAreDuplicates() throws Exception {
        submit(INPUT.replace(".123456", ".123000001"));
        submit(INPUT.replace(".123456", ".123999999"));
        assertThat(repository.findAll()).singleElement().satisfies(record ->
                assertEquals(Instant.parse("2026-10-21T12:00:00.123Z"), record.getTime()));
    }

    @Test
    void adjacentMillisecondsRemainDistinctWithoutRounding() throws Exception {
        submit(INPUT.replace(".123456", ".123999999"));
        submit(INPUT.replace(".123456", ".124000000"));
        assertThat(repository.findAll()).hasSize(2)
                .extracting(record -> record.getTime())
                .containsExactlyInAnyOrder(Instant.parse("2026-10-21T12:00:00.123Z"),
                        Instant.parse("2026-10-21T12:00:00.124Z"));
    }

    @Test
    void truncationDoesNotRoundIntoTheNextSecond() throws Exception {
        submit(INPUT.replace(".123456", ".999999999"));
        assertThat(repository.findAll()).singleElement().satisfies(record ->
                assertEquals(Instant.parse("2026-10-21T12:00:00.999Z"), record.getTime()));
    }

    @Test
    void concurrentDuplicateRequestsAllSucceedWithOneStoredRecord() throws Exception {
        int workers = 8;
        var start = new CyclicBarrier(workers);
        var tasks = new ArrayList<Callable<Integer>>();
        for (int index = 0; index < workers; index++) {
            tasks.add(() -> {
                start.await(10, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/couriers/locations")
                                .contentType(MediaType.APPLICATION_JSON).content(INPUT))
                        .andReturn().getResponse().getStatus();
            });
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            List<Future<Integer>> results = executor.invokeAll(tasks, 20, TimeUnit.SECONDS);
            for (Future<Integer> result : results) {
                assertEquals(204, result.get(1, TimeUnit.SECONDS));
            }
        }
        assertEquals(1L, repository.count());
    }

    private void submit(String body) throws Exception {
        mvc.perform(post("/api/v1/couriers/locations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"90.000001", "-90.000001", "1e309", "-1e309", "\"NaN\""})
    void invalidLatitudeReturnsBadRequestWithoutPersistence(String latitude) throws Exception {
        mvc.perform(post("/api/v1/couriers/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INPUT.replace("40.9923307", latitude)))
                .andExpect(status().isBadRequest());
        assertEquals(0L, repository.count());
        assertEquals(2L, courierRepository.count());
        assertThat(courierRepository.findAll()).allSatisfy(courier ->
                assertNull(courier.getLastProcessedTime()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"180.000001", "-180.000001", "1e309", "-1e309", "\"NaN\""})
    void invalidLongitudeReturnsBadRequestWithoutPersistence(String longitude) throws Exception {
        mvc.perform(post("/api/v1/couriers/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INPUT.replace("29.1244229", longitude)))
                .andExpect(status().isBadRequest());
        assertEquals(0L, repository.count());
        assertEquals(2L, courierRepository.count());
        assertThat(courierRepository.findAll()).allSatisfy(courier ->
                assertNull(courier.getLastProcessedTime()));
    }

    @Test
    void coordinateBoundsRemainValidForSubmission() throws Exception {
        submit(INPUT.replace("40.9923307", "90").replace("29.1244229", "180"));
        submit(INPUT.replace("40.9923307", "-90").replace("29.1244229", "-180"));
        assertEquals(2L, repository.count());
    }

    @Test
    void rejectedInputCreatesNoRecord() throws Exception {
        mvc.perform(post("/api/v1/couriers/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INPUT.replace("courier-1", " ")))
                .andExpect(status().isBadRequest());

        assertEquals(0L, repository.count());
        assertEquals(2L, courierRepository.count());
        assertThat(courierRepository.findAll()).allSatisfy(courier ->
                assertNull(courier.getLastProcessedTime()));
    }
}
