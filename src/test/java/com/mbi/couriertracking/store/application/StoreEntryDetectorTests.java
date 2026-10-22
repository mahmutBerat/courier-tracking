package com.mbi.couriertracking.store.application;

import com.mbi.couriertracking.common.geo.GeoLocation;
import com.mbi.couriertracking.common.geo.HaversineDistanceCalculatorStrategy;
import com.mbi.couriertracking.store.domain.Store;
import com.mbi.couriertracking.store.domain.StoreCatalog;
import com.mbi.couriertracking.store.domain.StoreEntry;
import com.mbi.couriertracking.store.domain.StoreProximityState;
import com.mbi.couriertracking.store.persistence.StoreEntryRepository;
import com.mbi.couriertracking.store.persistence.StoreProximityStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoreEntryDetectorTests {

    private static final Store ATASEHIR = new Store("Ataşehir MMM Migros", new GeoLocation(40.9923307, 29.1244229));
    private static final Store NOVADA = new Store("Novada MMM Migros", new GeoLocation(40.986106, 29.1161293));
    private static final Instant NOON = Instant.parse("2026-10-21T12:00:00Z");

    @Mock
    private StoreProximityStateRepository proximityStates;
    @Mock
    private StoreEntryRepository storeEntries;
    private StoreEntryDetector detector;

    @BeforeEach
    void setUp() {
        detector = detectorFor(ATASEHIR, NOVADA);
    }

    @Test
    void locationAtAStoreRecordsOneEntryForThatStoreOnly() {
        when(proximityStates.findByCourierId("courier-1")).thenReturn(List.of());

        detector.detectEntries("courier-1", ATASEHIR.location(), NOON);

        ArgumentCaptor<StoreEntry> savedEntry = ArgumentCaptor.forClass(StoreEntry.class);
        verify(storeEntries).save(savedEntry.capture());
        assertEquals("Ataşehir MMM Migros", savedEntry.getValue().getStoreName());
        assertEquals(NOON, savedEntry.getValue().getEntryTime());
        assertTrue(savedEntry.getValue().isCounted());

        ArgumentCaptor<StoreProximityState> savedState = ArgumentCaptor.forClass(StoreProximityState.class);
        verify(proximityStates).save(savedState.capture());
        assertEquals("Ataşehir MMM Migros", savedState.getValue().getStoreName());
        assertTrue(savedState.getValue().isInside());
    }

    @Test
    void locationFarFromEveryStoreRecordsNothing() {
        when(proximityStates.findByCourierId("courier-1")).thenReturn(List.of());

        detector.detectEntries("courier-1", new GeoLocation(0, 0), NOON);

        verify(proximityStates, never()).save(any());
        verifyNoInteractions(storeEntries);
    }

    @Test
    void existingPairStateDecidesWhetherTheReentryCounts() {
        StoreProximityState enteredThirtySecondsAgo = StoreProximityState.initial("courier-1", ATASEHIR.name());
        enteredThirtySecondsAgo.checkStoreEntry(0, NOON.minusSeconds(30));
        enteredThirtySecondsAgo.checkStoreEntry(500, NOON.minusSeconds(10));
        when(proximityStates.findByCourierId("courier-1")).thenReturn(List.of(enteredThirtySecondsAgo));

        detector.detectEntries("courier-1", ATASEHIR.location(), NOON);

        ArgumentCaptor<StoreEntry> savedEntry = ArgumentCaptor.forClass(StoreEntry.class);
        verify(storeEntries).save(savedEntry.capture());
        assertFalse(savedEntry.getValue().isCounted());
        verify(proximityStates).save(enteredThirtySecondsAgo);
    }

    @Test
    void loadsTheCourierPairStatesWithOneQuery() {
        when(proximityStates.findByCourierId("courier-1")).thenReturn(List.of());

        detector.detectEntries("courier-1", new GeoLocation(0, 0), NOON);

        verify(proximityStates, times(1)).findByCourierId("courier-1");
        verify(proximityStates, never()).findByCourierIdAndStoreName(any(), any());
    }

    @Test
    void stopsAtTheFirstStoreEnteredInCatalogOrder() {
        // Two catalog entries at the same coordinates: a report can only enter one store.
        Store sameSpot = new Store("Same spot", ATASEHIR.location());
        detector = detectorFor(ATASEHIR, sameSpot);
        when(proximityStates.findByCourierId("courier-1")).thenReturn(List.of());

        detector.detectEntries("courier-1", ATASEHIR.location(), NOON);

        ArgumentCaptor<StoreEntry> savedEntry = ArgumentCaptor.forClass(StoreEntry.class);
        verify(storeEntries, times(1)).save(savedEntry.capture());
        assertEquals(ATASEHIR.name(), savedEntry.getValue().getStoreName());
    }

    private StoreEntryDetector detectorFor(Store... stores) {
        return new StoreEntryDetector(new StoreCatalog(List.of(stores)), proximityStates, storeEntries,
                new HaversineDistanceCalculatorStrategy());
    }
}
