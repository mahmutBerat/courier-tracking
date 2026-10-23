package com.mbi.couriertracking.courier.api;

import com.mbi.couriertracking.courier.application.CourierAlreadyExistsException;
import com.mbi.couriertracking.courier.application.CourierNotFoundException;
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

    private static final String COURIERS_PATH = "/api/v1/couriers";
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
                .andExpect(errorResponse(404, "COURIER_NOT_FOUND", DISTANCE_PATH))
                .andExpect(jsonPath("$.message").value("Courier not found."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());

        verify(courierService).findTotalDistanceMeters("unknown");
    }

    @Test
    void submissionRejectsUnknownCourier() throws Exception {
        doThrow(new CourierNotFoundException("courier-1"))
                .when(courierService).submitLocation(EXPECTED_INPUT);

        mvc.perform(post(LOCATIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_LOCATION))
                .andExpect(errorResponse(404, "COURIER_NOT_FOUND", LOCATIONS_PATH))
                .andExpect(jsonPath("$.message").value("Courier not found."));
    }

    @Test
    void registrationRejectsExistingCourier() throws Exception {
        doThrow(new CourierAlreadyExistsException("courier-1"))
                .when(courierService).createCourier("courier-1");

        mvc.perform(post(COURIERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"courierId":"courier-1"}
                                """))
                .andExpect(errorResponse(409, "COURIER_ALREADY_EXISTS", COURIERS_PATH))
                .andExpect(jsonPath("$.message").value("Courier already exists."));
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidLocationBodies")
    void rejectsInvalidLocationBody(String scenario, String body, String expectedCode) throws Exception {
        mvc.perform(post(LOCATIONS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(errorResponse(400, expectedCode, LOCATIONS_PATH));

        verifyNoInteractions(courierService);
    }

    @Test
    void reportsFieldViolationsForInvalidCoordinates() throws Exception {
        String body = VALID_LOCATION.replace("40.9923307", "91").replace("29.1244229", "-181");

        mvc.perform(post(LOCATIONS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(errorResponse(400, "VALIDATION_FAILED", LOCATIONS_PATH))
                .andExpect(jsonPath("$.message").value("Request validation failed."))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'lat')].message").value("must be less than or equal to 90"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'lng')].message").value("must be greater than or equal to -180"));

        verifyNoInteractions(courierService);
    }

    private static Stream<Arguments> invalidLocationBodies() {
        return Stream.of(
                Arguments.of("empty object", "{}", "VALIDATION_FAILED"),
                Arguments.of("null body", "null", "BAD_REQUEST"),
                Arguments.of("malformed JSON", "{", "BAD_REQUEST"),
                Arguments.of("timestamp without offset",
                        VALID_LOCATION.replace("2026-10-21T12:00:00.123Z", "2026-10-21T12:00:00"), "BAD_REQUEST"),
                Arguments.of("invalid timestamp",
                        VALID_LOCATION.replace("2026-10-21T12:00:00.123Z", "invalid"), "BAD_REQUEST"),
                Arguments.of("blank courier", VALID_LOCATION.replace("courier-1", " "), "VALIDATION_FAILED"),
                Arguments.of("missing latitude", """
                        {"time":"2026-10-21T12:00:00Z","courier":"courier-1","lng":0}
                        """, "VALIDATION_FAILED"),
                Arguments.of("missing longitude", """
                        {"time":"2026-10-21T12:00:00Z","courier":"courier-1","lat":0}
                        """, "VALIDATION_FAILED")
        );
    }

    @Test
    void rejectsMissingCourierQueryParameter() throws Exception {
        mvc.perform(get(DISTANCE_PATH))
                .andExpect(errorResponse(400, "BAD_REQUEST", DISTANCE_PATH));

        verifyNoInteractions(courierService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void rejectsBlankCourierQueryParameter(String courierId) throws Exception {
        mvc.perform(get(DISTANCE_PATH).param("courierId", courierId))
                .andExpect(errorResponse(400, "VALIDATION_FAILED", DISTANCE_PATH))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("courierId"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must not be blank"));

        verifyNoInteractions(courierService);
    }

    @Test
    void rejectsNonJsonSubmission() throws Exception {
        mvc.perform(post(LOCATIONS_PATH).contentType(MediaType.TEXT_PLAIN).content(VALID_LOCATION))
                .andExpect(errorResponse(415, "UNSUPPORTED_MEDIA_TYPE", LOCATIONS_PATH));

        verifyNoInteractions(courierService);
    }

    private static ResultMatcher errorResponse(int expectedStatus, String expectedCode, String path) {
        return result -> {
            status().is(expectedStatus).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON).match(result);
            jsonPath("$.status").value(expectedStatus).match(result);
            jsonPath("$.error").value(HttpStatus.valueOf(expectedStatus).getReasonPhrase()).match(result);
            jsonPath("$.code").value(expectedCode).match(result);
            jsonPath("$.path").value(path).match(result);
        };
    }
}
