package com.mbi.couriertracking.courier.messaging;

import com.mbi.couriertracking.courier.api.CourierLocationInput;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CourierLocationMessageMapperTests {

    @Test
    void mapsEveryMessageFieldToTheInput() {
        CourierLocationMessageMapper mapper = new CourierLocationMessageMapperImpl();
        var message = new CourierLocationMessage(Instant.parse("2026-10-21T12:00:00.123Z"), "courier-1", 40.9923307, 29.1244229);

        CourierLocationInput input = mapper.toInput(message);

        assertEquals(new CourierLocationInput(Instant.parse("2026-10-21T12:00:00.123Z"), "courier-1", 40.9923307, 29.1244229), input);
    }
}
