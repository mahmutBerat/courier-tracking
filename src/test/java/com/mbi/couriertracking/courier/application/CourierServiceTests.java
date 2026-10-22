package com.mbi.couriertracking.courier.application;

import com.mbi.couriertracking.common.geo.GeoLocation;
import com.mbi.couriertracking.common.geo.HaversineDistanceCalculatorStrategy;
import com.mbi.couriertracking.courier.api.CourierLocationInput;
import com.mbi.couriertracking.courier.domain.Courier;
import com.mbi.couriertracking.courier.persistence.CourierLocationMapperImpl;
import com.mbi.couriertracking.courier.persistence.CourierLocationRepository;
import com.mbi.couriertracking.courier.persistence.CourierRepository;
import com.mbi.couriertracking.store.application.StoreEntryDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourierServiceTests {

    @Mock
    private CourierLocationRepository inputs;
    @Mock
    private CourierRepository couriers;
    @Mock
    private StoreEntryDetector storeEntryDetector;
    private CourierService service;
    private final CourierLocationInput input = new CourierLocationInput(
            Instant.parse("2026-10-21T12:00:00.123456Z"), "courier-1", 40.9923307, 29.1244229);

    @BeforeEach
    void setUp() {
        service = new CourierService(inputs, new CourierLocationMapperImpl(),
                couriers, new HaversineDistanceCalculatorStrategy(), storeEntryDetector);
    }

    @Test
    void storageFailureDoesNotReportSuccessfulSubmission() {
        var failure = new DataAccessResourceFailureException("Database unavailable");
        when(couriers.findForUpdate("courier-1")).thenReturn(Optional.of(new Courier("courier-1")));
        when(inputs.insertIfAbsent(any())).thenThrow(failure);

        DataAccessResourceFailureException thrown = assertThrows(DataAccessResourceFailureException.class,
                () -> service.submitLocation(input));
        assertSame(failure, thrown);
        verify(couriers, never()).insertIfAbsent(any());
    }

    @Test
    void duplicateDoesNotModifyLockedCourierState() {
        var courier = new Courier("courier-1");
        when(couriers.findForUpdate("courier-1")).thenReturn(Optional.of(courier));
        when(inputs.insertIfAbsent(any())).thenReturn(0);

        service.submitLocation(input);

        assertNull(courier.getLastProcessedTime());
        assertNull(courier.getLastLatitude());
        assertEquals(0.0, courier.getTotalDistanceMeters());
        verify(couriers, never()).insertIfAbsent(any());
        verifyNoInteractions(storeEntryDetector);
    }

    @Test
    void processedInputIsEvaluatedForStoreEntriesWithItsOwnTime() {
        var courier = new Courier("courier-1");
        when(couriers.findForUpdate("courier-1")).thenReturn(Optional.of(courier));
        when(inputs.insertIfAbsent(any())).thenReturn(1);

        service.submitLocation(input);

        verify(storeEntryDetector).detectEntries("courier-1", new GeoLocation(input.lat(), input.lng()),
                Instant.parse("2026-10-21T12:00:00.123Z"));
    }

    @Test
    void staleInputIsStoredButNotEvaluatedForStoreEntries() {
        var courier = new Courier("courier-1");
        courier.updateDistance(new GeoLocation(0, 0), input.time().plusSeconds(10), new HaversineDistanceCalculatorStrategy());
        when(couriers.findForUpdate("courier-1")).thenReturn(Optional.of(courier));
        when(inputs.insertIfAbsent(any())).thenReturn(1);

        service.submitLocation(input);

        verifyNoInteractions(storeEntryDetector);
    }

    @Test
    void unknownCourierIsRejectedBeforeInputStorage() {
        when(couriers.findForUpdate("courier-1")).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.submitLocation(input));
        assertEquals(404, error.getStatusCode().value());
        verifyNoInteractions(inputs);
    }

    @Test
    void duplicateRegistrationReturnsConflict() {
        when(couriers.insertIfAbsent("courier-1")).thenReturn(0);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.createCourier("courier-1"));
        assertEquals(409, error.getStatusCode().value());
        verifyNoInteractions(inputs);
    }

    @Test
    void firstInputInitializesLockedCourierUsingGeneratedCoordinateMapping() {
        var courier = new Courier("courier-1");
        when(inputs.insertIfAbsent(any())).thenReturn(1);
        when(couriers.findForUpdate("courier-1")).thenReturn(Optional.of(courier));

        service.submitLocation(input);

        assertEquals(input.lat(), courier.getLastLatitude());
        assertEquals(input.lng(), courier.getLastLongitude());
        assertEquals(Instant.parse("2026-10-21T12:00:00.123Z"), courier.getLastProcessedTime());
        assertEquals(0.0, courier.getTotalDistanceMeters());
        verify(couriers, never()).insertIfAbsent(any());
    }
    @Test
    void registeredCourierWithoutLocationsHasZeroDistance() {
        when(couriers.findById("courier-1")).thenReturn(Optional.of(new Courier("courier-1")));

        assertThat(service.findTotalDistanceMeters("courier-1")).hasValue(0);
        verifyNoInteractions(inputs);
        verify(couriers, never()).findForUpdate(any());
    }

    @Test
    void distanceQueryReadsStoredTotalWithoutHistory() {
        var courier = new Courier("courier-1");
        var calculator = new HaversineDistanceCalculatorStrategy();
        courier.updateDistance(new GeoLocation(0, 0), input.time(), calculator);
        courier.updateDistance(new GeoLocation(0, 1), input.time().plusSeconds(1), calculator);
        when(couriers.findById("courier-1")).thenReturn(Optional.of(courier));

        assertThat(service.findTotalDistanceMeters("courier-1")).hasValue(courier.getTotalDistanceMeters());
        verifyNoInteractions(inputs);
        verify(couriers, never()).findForUpdate(any());
    }

    @Test
    void unknownCourierHasNoDistanceAndIsNotCreated() {
        when(couriers.findById("missing")).thenReturn(Optional.empty());

        assertTrue(service.findTotalDistanceMeters("missing").isEmpty());
        verifyNoInteractions(inputs);
        verify(couriers, never()).insertIfAbsent(any());
    }
}
