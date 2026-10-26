package com.mbi.couriertracking.store.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class StoreVisitTests {

    private static final String COURIER = "courier-1";
    private static final String ATASEHIR = "Ataşehir MMM Migros";
    private static final Instant NOON = Instant.parse("2026-10-21T12:00:00Z");

    @Test
    void openedVisitIsOpenAtItsEntryTime() {
        StoreVisit visit = StoreVisit.open(COURIER, ATASEHIR, NOON);

        assertEquals(COURIER, visit.getCourierId());
        assertEquals(ATASEHIR, visit.getStoreName());
        assertEquals(NOON, visit.getEntryTime());
        assertNull(visit.getExitTime());
        assertTrue(visit.isOpen());
    }

    @Test
    void closingSetsTheExitTimeAndClosingAgainFails() {
        StoreVisit visit = StoreVisit.open(COURIER, ATASEHIR, NOON);
        visit.close(NOON.plusSeconds(5));

        assertFalse(visit.isOpen());
        assertEquals(NOON.plusSeconds(5), visit.getExitTime());
        assertThrows(IllegalStateException.class, () -> visit.close(NOON.plusSeconds(6)));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 40, 59})
    void reentryBeforeSixtySecondsContinuesTheVisit(long secondsAfterEntry) {
        StoreVisit visit = closedVisit(NOON, NOON.plusSeconds(1));

        assertTrue(visit.continuesAt(NOON.plusSeconds(secondsAfterEntry)));
    }

    @ParameterizedTest
    @ValueSource(longs = {60, 61, 3600})
    void reentryAtOrAfterSixtySecondsIsANewEntrance(long secondsAfterEntry) {
        StoreVisit visit = closedVisit(NOON, NOON.plusSeconds(10));

        assertFalse(visit.continuesAt(NOON.plusSeconds(secondsAfterEntry)));
    }

    @Test
    void intervalIsMeasuredFromTheEntryNotFromTheExit() {
        StoreVisit visit = closedVisit(NOON, NOON.plusSeconds(55));

        assertTrue(visit.continuesAt(NOON.plusSeconds(59)), "4 s after the exit but 59 s after the entry");
        assertFalse(visit.continuesAt(NOON.plusSeconds(60)));
    }

    @Test
    void resumingReopensAClosedVisitAndKeepsItsEntryTime() {
        StoreVisit visit = closedVisit(NOON, NOON.plusSeconds(30));

        visit.resume();

        assertTrue(visit.isOpen());
        assertNull(visit.getExitTime());
        assertEquals(NOON, visit.getEntryTime());
        visit.close(NOON.plusSeconds(90));
        assertEquals(NOON.plusSeconds(90), visit.getExitTime());
    }

    @Test
    void resumingAnOpenVisitFails() {
        StoreVisit open = StoreVisit.open(COURIER, ATASEHIR, NOON);

        assertThrows(IllegalStateException.class, open::resume);
    }

    @Test
    void boundaryIsInclusive() {
        assertTrue(StoreVisit.isInside(100));
        assertTrue(StoreVisit.isInside(99.999));
        assertFalse(StoreVisit.isInside(100.001));
    }

    private static StoreVisit closedVisit(Instant entryTime, Instant exitTime) {
        StoreVisit visit = StoreVisit.open(COURIER, ATASEHIR, entryTime);
        visit.close(exitTime);
        return visit;
    }
}
