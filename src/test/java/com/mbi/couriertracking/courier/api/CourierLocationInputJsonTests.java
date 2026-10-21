package com.mbi.couriertracking.courier.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

@JsonTest
class CourierLocationInputJsonTests {

    @Autowired
    private JsonMapper mapper;

    @Test
    void bindsSourceFieldNamesAndUtcTimestamp() {
        // Given
        String json = """
                {
                  "time": "2026-10-21T12:00:00.123Z",
                  "courier": "courier-1",
                  "lat": 40.9923307,
                  "lng": 29.1244229
                }
                """;
        CourierLocationInput expected = new CourierLocationInput(
                Instant.parse("2026-10-21T12:00:00.123Z"), "courier-1", 40.9923307, 29.1244229);

        // When
        CourierLocationInput actual = mapper.readValue(json, CourierLocationInput.class);

        // Then
        assertEquals(expected, actual);
    }
}
