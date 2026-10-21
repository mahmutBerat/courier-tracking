package com.mbi.couriertracking.courier.api;

import com.mbi.couriertracking.courier.application.CourierService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.OptionalDouble;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CourierController.class)
@Import(CourierMapperImpl.class)
class CourierControllerTests {

    private static final String LOCATIONS_PATH = "/api/v1/couriers/locations";
    private static final String DISTANCE_PATH = "/api/v1/couriers/distance";
    private static final String VALID_LOCATION = """
            {
              "time": "2026-10-21T12:00:00.123Z",
              "courier": "courier-1",
              "lat": 40.9923307,
              "lng": 29.1244229
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CourierService courierService;

    private static final CourierLocationInput EXPECTED_INPUT = new CourierLocationInput(
            Instant.parse("2026-10-21T12:00:00.123Z"), "courier-1", 40.9923307, 29.1244229);

    @Test
    void returnsNoContentAfterSubmittingCompleteInput() throws Exception {
        mvc.perform(post(LOCATIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_LOCATION))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(courierService).submitLocation(EXPECTED_INPUT);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1234.56})
    void returnsDistanceInMetersForKnownCourier(double distanceMeters) throws Exception {
        when(courierService.findTotalDistanceMeters("courier-1"))
                .thenReturn(OptionalDouble.of(distanceMeters));

        mvc.perform(get(DISTANCE_PATH).param("courierId", "courier-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.courierId").value("courier-1"))
                .andExpect(jsonPath("$.distanceMeters").value(distanceMeters));

        verify(courierService).findTotalDistanceMeters("courier-1");
    }

    @Test
    void returnsNotFoundForUnknownCourier() throws Exception {
        when(courierService.findTotalDistanceMeters("unknown")).thenReturn(OptionalDouble.empty());

        mvc.perform(get(DISTANCE_PATH).param("courierId", "unknown"))
                .andExpect(problemResponse(404, DISTANCE_PATH))
                .andExpect(jsonPath("$.detail").value("Courier not found."));

        verify(courierService).findTotalDistanceMeters("unknown");
    }

    @Test
    void submissionRejectsUnknownCourier() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Courier not found."))
                .when(courierService).submitLocation(EXPECTED_INPUT);

        mvc.perform(post(LOCATIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_LOCATION))
                .andExpect(problemResponse(404, LOCATIONS_PATH))
                .andExpect(jsonPath("$.detail").value("Courier not found."));
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidLocationBodies")
    void rejectsInvalidLocationBody(String scenario, String body) throws Exception {
        mvc.perform(post(LOCATIONS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(problemResponse(400, LOCATIONS_PATH));

        verifyNoInteractions(courierService);
    }

    private static Stream<Arguments> invalidLocationBodies() {
        return Stream.of(
                Arguments.of("empty object", "{}"),
                Arguments.of("null body", "null"),
                Arguments.of("malformed JSON", "{"),
                Arguments.of("timestamp without offset",
                        VALID_LOCATION.replace("2026-10-21T12:00:00.123Z", "2026-10-21T12:00:00")),
                Arguments.of("invalid timestamp",
                        VALID_LOCATION.replace("2026-10-21T12:00:00.123Z", "invalid")),
                Arguments.of("blank courier", VALID_LOCATION.replace("courier-1", " ")),
                Arguments.of("missing latitude", """
                        {"time":"2026-10-21T12:00:00Z","courier":"courier-1","lng":0}
                        """),
                Arguments.of("missing longitude", """
                        {"time":"2026-10-21T12:00:00Z","courier":"courier-1","lat":0}
                        """)
        );
    }

    @Test
    void rejectsMissingCourierQueryParameter() throws Exception {
        mvc.perform(get(DISTANCE_PATH))
                .andExpect(problemResponse(400, DISTANCE_PATH));

        verifyNoInteractions(courierService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void rejectsBlankCourierQueryParameter(String courierId) throws Exception {
        mvc.perform(get(DISTANCE_PATH).param("courierId", courierId))
                .andExpect(problemResponse(400, DISTANCE_PATH));

        verifyNoInteractions(courierService);
    }

    @Test
    void rejectsNonJsonSubmission() throws Exception {
        mvc.perform(post(LOCATIONS_PATH).contentType(MediaType.TEXT_PLAIN).content(VALID_LOCATION))
                .andExpect(problemResponse(415, LOCATIONS_PATH));

        verifyNoInteractions(courierService);
    }

    private static ResultMatcher problemResponse(int expectedStatus, String path) {
        return result -> {
            status().is(expectedStatus).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.status").value(expectedStatus).match(result);
            jsonPath("$.instance").value(path).match(result);
        };
    }
}
