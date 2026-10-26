package com.mbi.couriertracking.store.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CourierStoreVisitsTests {

    private static final String COURIER = "courier-1";
    private static final String ATASEHIR = "Ataşehir MMM Migros";
    private static final String NOVADA = "Novada MMM Migros";
    private static final Instant NOON = Instant.parse("2026-10-21T12:00:00Z");

    @Test
    void firstObservationInsideOpensAVisit() {
        CourierStoreVisits visits = CourierStoreVisits.of(COURIER, List.of());

        StoreVisit opened = visits.checkProximity(ATASEHIR, 50, NOON).orElseThrow();

        assertEquals(COURIER, opened.getCourierId());
        assertEquals(ATASEHIR, opened.getStoreName());
        assertEquals(NOON, opened.getEntryTime());
        assertTrue(opened.isOpen());
        assertSame(opened, visits.outcome().entered().orElseThrow());
        assertTrue(visits.outcome().entrance());
        assertEquals(Optional.of(ATASEHIR), visits.outcome().currentStoreName());
    }

    @Test
    void firstObservationOutsideChangesNothing() {
        CourierStoreVisits visits = CourierStoreVisits.of(COURIER, List.of());

        assertTrue(visits.checkProximity(ATASEHIR, 500, NOON).isEmpty());
        assertEquals(StoreVisitResult.outside(), visits.outcome());
    }

    @Test
    void stayingInsideChangesNothingAndKeepsTheCurrentStore() {
        StoreVisit open = StoreVisit.open(COURIER, ATASEHIR, NOON.minusSeconds(30));
        CourierStoreVisits visits = CourierStoreVisits.of(COURIER, List.of(open));

        assertTrue(visits.checkProximity(ATASEHIR, 50, NOON).isEmpty());

        assertTrue(open.isOpen());
        assertTrue(visits.outcome().entered().isEmpty());
        assertFalse(visits.outcome().entrance());
        assertEquals(Optional.of(ATASEHIR), visits.outcome().currentStoreName());
    }

    @Test
    void leavingClosesTheOpenVisit() {
        StoreVisit open = StoreVisit.open(COURIER, ATASEHIR, NOON.minusSeconds(30));
        CourierStoreVisits visits = CourierStoreVisits.of(COURIER, List.of(open));

        StoreVisit closed = visits.checkProximity(ATASEHIR, 500, NOON).orElseThrow();

        assertSame(open, closed);
        assertEquals(NOON, closed.getExitTime());
        assertEquals(StoreVisitResult.outside(), visits.outcome());
    }

    @Test
    void stayingOutsideAfterAnExitChangesNothing() {
        StoreVisit closed = StoreVisit.open(COURIER, ATASEHIR, NOON.minusSeconds(30));
        closed.close(NOON.minusSeconds(10));
        CourierStoreVisits visits = CourierStoreVisits.of(COURIER, List.of(closed));

        assertTrue(visits.checkProximity(ATASEHIR, 500, NOON).isEmpty());
        assertEquals(StoreVisitResult.outside(), visits.outcome());
    }

    @Test
    void reentryWithinOneMinuteResumesTheSameVisitInsteadOfOpeningANewOne() {
        // Owner's case: entered Novada at 12:18:35, left, back inside at 12:18:55 -> still one row.
        Instant entry = Instant.parse("2026-10-21T12:18:35Z");
        StoreVisit previous = StoreVisit.open(COURIER, NOVADA, entry);
        previous.close(Instant.parse("2026-10-21T12:18:45Z"));
        CourierStoreVisits visits = CourierStoreVisits.of(COURIER, List.of(previous));

        StoreVisit resumed = visits.checkProximity(NOVADA, 50, Instant.parse("2026-10-21T12:18:55Z")).orElseThrow();

        assertSame(previous, resumed);
        assertTrue(resumed.isOpen());
        assertEquals(entry, resumed.getEntryTime());
        assertSame(previous, visits.outcome().entered().orElseThrow());
        assertFalse(visits.outcome().entrance(), "a resumed visit is not a new entrance");
        assertEquals(Optional.of(NOVADA), visits.outcome().currentStoreName());
    }

    @Test
    void leavingAfterAResumedVisitClosesItAgain() {
        StoreVisit previous = StoreVisit.open(COURIER, ATASEHIR, NOON.minusSeconds(30));
        previous.close(NOON.minusSeconds(20));
        CourierStoreVisits visits = CourierStoreVisits.of(COURIER, List.of(previous));
        visits.checkProximity(ATASEHIR, 50, NOON.minusSeconds(10));

        StoreVisit closed = visits.checkProximity(ATASEHIR, 500, NOON).orElseThrow();

        assertSame(previous, closed);
        assertEquals(NOON, closed.getExitTime());
    }

    @Test
    void reentryAtOrAfterOneMinuteOpensANewVisit() {
        StoreVisit previous = StoreVisit.open(COURIER, ATASEHIR, NOON.minusSeconds(60));
        previous.close(NOON.minusSeconds(10));
        CourierStoreVisits visits = CourierStoreVisits.of(COURIER, List.of(previous));

        StoreVisit reentry = visits.checkProximity(ATASEHIR, 50, NOON).orElseThrow();

        assertNotSame(previous, reentry);
        assertEquals(NOON, reentry.getEntryTime());
        assertFalse(previous.isOpen(), "previous visit stays closed");
        assertTrue(visits.outcome().entrance());
        assertEquals(Optional.of(ATASEHIR), visits.outcome().currentStoreName());
    }

    @Test
    void exactlyOneHundredMetersIsInsideAndJustOverIsOutside() {
        CourierStoreVisits visits = CourierStoreVisits.of(COURIER, List.of());

        StoreVisit opened = visits.checkProximity(ATASEHIR, 100, NOON).orElseThrow();
        StoreVisit closed = visits.checkProximity(ATASEHIR, 100.001, NOON.plusSeconds(5)).orElseThrow();

        assertSame(opened, closed);
        assertEquals(NOON.plusSeconds(5), closed.getExitTime());
    }

    @Test
    void movingToAnotherStoreClosesTheLeftVisitAndReportsTheEnteredOne() {
        StoreVisit insideNovada = StoreVisit.open(COURIER, NOVADA, NOON.minusSeconds(120));
        CourierStoreVisits visits = CourierStoreVisits.of(COURIER, List.of(insideNovada));

        StoreVisit entered = visits.checkProximity(ATASEHIR, 50, NOON).orElseThrow();
        StoreVisit left = visits.checkProximity(NOVADA, 1000, NOON).orElseThrow();

        assertSame(insideNovada, left);
        assertFalse(left.isOpen());
        assertSame(entered, visits.outcome().entered().orElseThrow());
        assertEquals(Optional.of(ATASEHIR), visits.outcome().currentStoreName());
    }

    @Test
    void closingTheLatestVisitFallsBackToTheMostRecentlyEnteredOpenStore() {
        // Synthetic overlap: both visits open, the courier stays at Ataşehir and leaves Novada.
        StoreVisit openAtasehir = StoreVisit.open(COURIER, ATASEHIR, NOON.minusSeconds(100));
        StoreVisit openNovada = StoreVisit.open(COURIER, NOVADA, NOON.minusSeconds(50));
        CourierStoreVisits visits = CourierStoreVisits.of(COURIER, List.of(openAtasehir, openNovada));

        assertTrue(visits.checkProximity(ATASEHIR, 50, NOON).isEmpty());
        assertSame(openNovada, visits.checkProximity(NOVADA, 1000, NOON).orElseThrow());

        assertTrue(visits.outcome().entered().isEmpty());
        assertEquals(Optional.of(ATASEHIR), visits.outcome().currentStoreName());
    }
}
