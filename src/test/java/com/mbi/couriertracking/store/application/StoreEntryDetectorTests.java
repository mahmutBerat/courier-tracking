package com.mbi.couriertracking.store.application;

import com.mbi.couriertracking.common.geo.GeoLocation;
import com.mbi.couriertracking.common.geo.HaversineDistanceCalculatorStrategy;
import com.mbi.couriertracking.store.domain.Store;
import com.mbi.couriertracking.store.domain.StoreCatalog;
import com.mbi.couriertracking.store.domain.StoreVisit;
import com.mbi.couriertracking.store.domain.StoreVisitResult;
import com.mbi.couriertracking.store.persistence.StoreVisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoreEntryDetectorTests {

    private static final Store ATASEHIR = new Store("Ataşehir MMM Migros", new GeoLocation(40.9923307, 29.1244229));
    private static final Store NOVADA = new Store("Novada MMM Migros", new GeoLocation(40.986106, 29.1161293));
    private static final GeoLocation FAR_AWAY = new GeoLocation(0, 0);
    private static final Instant NOON = Instant.parse("2026-10-21T12:00:00Z");

    @Mock
    private StoreVisitRepository storeVisits;
    private StoreEntryDetector detector;

    @BeforeEach
    void setUp() {
        detector = new StoreEntryDetector(new StoreCatalog(List.of(ATASEHIR, NOVADA)), storeVisits,
                new HaversineDistanceCalculatorStrategy());
    }

    @Test
    void locationAtAStoreOpensOneVisitForThatStoreOnly() {
        when(storeVisits.findLatestPerStore("courier-1")).thenReturn(List.of());

        StoreVisitResult outcome = detector.detectEntries("courier-1", ATASEHIR.location(), NOON);

        ArgumentCaptor<StoreVisit> saved = ArgumentCaptor.forClass(StoreVisit.class);
        verify(storeVisits, times(1)).save(saved.capture());
        assertEquals(ATASEHIR.name(), saved.getValue().getStoreName());
        assertEquals(NOON, saved.getValue().getEntryTime());
        assertTrue(saved.getValue().isOpen());
        assertSame(saved.getValue(), outcome.entered().orElseThrow());
        assertTrue(outcome.entrance());
        assertEquals(Optional.of(ATASEHIR.name()), outcome.currentStoreName());
    }

    @Test
    void locationFarFromEveryStoreWritesNothing() {
        when(storeVisits.findLatestPerStore("courier-1")).thenReturn(List.of());

        StoreVisitResult outcome = detector.detectEntries("courier-1", FAR_AWAY, NOON);

        assertEquals(StoreVisitResult.outside(), outcome);
        verify(storeVisits, never()).save(any());
    }

    @Test
    void reentryWithinOneMinuteResumesThePreviousVisitInsteadOfSavingANewOne() {
        StoreVisit previous = StoreVisit.open("courier-1", ATASEHIR.name(), NOON.minusSeconds(30));
        previous.close(NOON.minusSeconds(10));
        when(storeVisits.findLatestPerStore("courier-1")).thenReturn(List.of(previous));

        StoreVisitResult outcome = detector.detectEntries("courier-1", ATASEHIR.location(), NOON);

        assertSame(previous, outcome.entered().orElseThrow());
        assertFalse(outcome.entrance());
        assertTrue(previous.isOpen());
        assertEquals(NOON.minusSeconds(30), previous.getEntryTime());
        verify(storeVisits, times(1)).save(previous);
    }

    @Test
    void leavingTheStoreClosesTheOpenVisit() {
        StoreVisit open = StoreVisit.open("courier-1", ATASEHIR.name(), NOON.minusSeconds(30));
        when(storeVisits.findLatestPerStore("courier-1")).thenReturn(List.of(open));

        StoreVisitResult outcome = detector.detectEntries("courier-1", FAR_AWAY, NOON);

        assertFalse(open.isOpen());
        assertEquals(NOON, open.getExitTime());
        verify(storeVisits).save(open);
        assertTrue(outcome.entered().isEmpty());
        assertTrue(outcome.currentStoreName().isEmpty());
    }

    @Test
    void stayingOutsideAfterExitWritesNothing() {
        StoreVisit closed = StoreVisit.open("courier-1", ATASEHIR.name(), NOON.minusSeconds(30));
        closed.close(NOON.minusSeconds(10));
        when(storeVisits.findLatestPerStore("courier-1")).thenReturn(List.of(closed));

        StoreVisitResult outcome = detector.detectEntries("courier-1", FAR_AWAY, NOON);

        assertEquals(StoreVisitResult.outside(), outcome);
        verify(storeVisits, never()).save(any());
    }

    @Test
    void stayingInsideSavesNothingAndKeepsCurrentStore() {
        StoreVisit open = StoreVisit.open("courier-1", ATASEHIR.name(), NOON.minusSeconds(30));
        when(storeVisits.findLatestPerStore("courier-1")).thenReturn(List.of(open));

        StoreVisitResult outcome = detector.detectEntries("courier-1", ATASEHIR.location(), NOON);

        verify(storeVisits, never()).save(any());
        assertTrue(outcome.entered().isEmpty());
        assertEquals(Optional.of(ATASEHIR.name()), outcome.currentStoreName());
    }

    @Test
    void movingToAnotherStoreClosesTheLeftVisitAndOpensTheNewOne() {
        // Novada follows Ataşehir in the catalog; its exit must be observed although Ataşehir was entered.
        StoreVisit insideNovada = StoreVisit.open("courier-1", NOVADA.name(), NOON.minusSeconds(120));
        when(storeVisits.findLatestPerStore("courier-1")).thenReturn(List.of(insideNovada));

        StoreVisitResult outcome = detector.detectEntries("courier-1", ATASEHIR.location(), NOON);

        assertFalse(insideNovada.isOpen());
        assertEquals(NOON, insideNovada.getExitTime());
        ArgumentCaptor<StoreVisit> saved = ArgumentCaptor.forClass(StoreVisit.class);
        verify(storeVisits, times(2)).save(saved.capture());
        assertTrue(saved.getAllValues().contains(insideNovada));
        assertEquals(ATASEHIR.name(), outcome.entered().orElseThrow().getStoreName());
        assertEquals(Optional.of(ATASEHIR.name()), outcome.currentStoreName());
    }

    @Test
    void closingTheLatestVisitFallsBackToTheOtherOpenStore() {
        // Synthetic overlap: both visits open, the courier is still at Ataşehir but no longer near Novada.
        StoreVisit openAtasehir = StoreVisit.open("courier-1", ATASEHIR.name(), NOON.minusSeconds(100));
        StoreVisit openNovada = StoreVisit.open("courier-1", NOVADA.name(), NOON.minusSeconds(50));
        when(storeVisits.findLatestPerStore("courier-1")).thenReturn(List.of(openAtasehir, openNovada));

        StoreVisitResult outcome = detector.detectEntries("courier-1", ATASEHIR.location(), NOON);

        assertFalse(openNovada.isOpen());
        assertTrue(openAtasehir.isOpen());
        verify(storeVisits, times(1)).save(openNovada);
        assertTrue(outcome.entered().isEmpty());
        assertEquals(Optional.of(ATASEHIR.name()), outcome.currentStoreName());
    }

    @Test
    void loadsLatestVisitsWithOneQuery() {
        when(storeVisits.findLatestPerStore("courier-1")).thenReturn(List.of());

        detector.detectEntries("courier-1", FAR_AWAY, NOON);

        verify(storeVisits, times(1)).findLatestPerStore("courier-1");
        verify(storeVisits, never()).findByCourierIdOrderByEntryTime(any());
    }
}
