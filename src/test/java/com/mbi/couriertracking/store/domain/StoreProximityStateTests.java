package com.mbi.couriertracking.store.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static com.mbi.couriertracking.store.domain.ProximityCheck.Transition.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Entry rules of one courier–store pair (S001-AC01–AC17).
 * Distances: 50 m is clearly inside, 500 m clearly outside, 100 m is the inclusive boundary.
 */
class StoreProximityStateTests {

    private static final double INSIDE = 50;
    private static final double OUTSIDE = 500;
    private static final Instant NOON = Instant.parse("2026-10-21T12:00:00Z");

    @Test
    void firstObservationInsideIsACountedEntry() {
        StoreProximityState state = StoreProximityState.initial("courier-1", "Ataşehir MMM Migros");

        ProximityCheck entry = state.checkProximity(INSIDE, NOON);

        assertTrue(state.isInside());
        assertEquals(NOON, state.getLastPhysicalEntryTime());
        assertEntry(entry, true, NOON);
        assertEquals("courier-1", entry.entry().orElseThrow().getCourierId());
        assertEquals("Ataşehir MMM Migros", entry.entry().orElseThrow().getStoreName());
    }

    @Test
    void firstObservationOutsideIsNotAnEntry() {
        StoreProximityState state = StoreProximityState.initial("courier-1", "store");

        assertEquals(UNCHANGED, state.checkProximity(OUTSIDE, NOON).transition());
        assertFalse(state.isInside());
        assertNull(state.getLastPhysicalEntryTime());
    }

    @Test
    void stayingInsideIsNotANewEntryAndKeepsTheEntryTime() {
        StoreProximityState state = enteredAt(NOON);

        assertEquals(UNCHANGED, state.checkProximity(INSIDE, NOON.plusSeconds(20)).transition());
        assertTrue(state.isInside());
        assertEquals(NOON, state.getLastPhysicalEntryTime());
    }

    @Test
    void reentryWithinOneMinuteIsRecordedButNotCountedAndMovesTheEntryTime() {
        StoreProximityState state = enteredAt(NOON);
        exit(state, NOON.plusSeconds(10));

        ProximityCheck reentry = state.checkProximity(INSIDE, NOON.plusSeconds(40));

        assertEntry(reentry, false, NOON.plusSeconds(40));
        assertEquals(NOON.plusSeconds(40), state.getLastPhysicalEntryTime());
    }

    @Test
    void intervalIsMeasuredFromThePreviousPhysicalEntryEvenWhenItWasNotCounted() {
        // 12:00:00 counted, 12:00:40 not counted, 12:01:10 is 70 s after the first but 30 s after the last.
        StoreProximityState state = enteredAt(NOON);
        exit(state, NOON.plusSeconds(10));
        state.checkProximity(INSIDE, NOON.plusSeconds(40));
        exit(state, NOON.plusSeconds(50));

        ProximityCheck thirdEntry = state.checkProximity(INSIDE, NOON.plusSeconds(70));

        assertEntry(thirdEntry, false, NOON.plusSeconds(70));
    }

    @Test
    void reentryAfterAnUncountedEntryCountsOnceSixtySecondsHavePassedSinceIt() {
        StoreProximityState state = enteredAt(NOON);
        exit(state, NOON.plusSeconds(10));
        state.checkProximity(INSIDE, NOON.plusSeconds(40)); // not counted
        exit(state, NOON.plusSeconds(50));

        ProximityCheck reentry = state.checkProximity(INSIDE, NOON.plusSeconds(100)); // 60 s after 12:00:40

        assertEntry(reentry, true, NOON.plusSeconds(100));
    }

    @ParameterizedTest
    @ValueSource(longs = {60, 61})
    void reentryAtOrAfterSixtySecondsIsCounted(long secondsAfterEntry) {
        StoreProximityState state = enteredAt(NOON);
        exit(state, NOON.plusSeconds(10));

        ProximityCheck reentry = state.checkProximity(INSIDE, NOON.plusSeconds(secondsAfterEntry));

        assertEntry(reentry, true, NOON.plusSeconds(secondsAfterEntry));
    }

    @Test
    void reentryAtFiftyNineSecondsIsNotCounted() {
        StoreProximityState state = enteredAt(NOON);
        exit(state, NOON.plusSeconds(10));

        ProximityCheck reentry = state.checkProximity(INSIDE, NOON.plusSeconds(59));

        assertEntry(reentry, false, NOON.plusSeconds(59));
        assertEquals(NOON.plusSeconds(59), state.getLastPhysicalEntryTime());
    }

    @ParameterizedTest
    @ValueSource(doubles = {99.999, 100})
    void distanceUpToAndIncludingOneHundredMetersIsInside(double distanceMeters) {
        StoreProximityState state = StoreProximityState.initial("courier-1", "store");

        assertEntry(state.checkProximity(distanceMeters, NOON), true, NOON);
    }

    @Test
    void exactlyOneHundredMetersWhileInsideRemainsInsideWithoutANewEntry() {
        StoreProximityState state = enteredAt(NOON);

        assertEquals(UNCHANGED, state.checkProximity(100, NOON.plusSeconds(5)).transition());
        assertTrue(state.isInside());
        assertEquals(NOON, state.getLastPhysicalEntryTime());
    }

    @Test
    void justOverOneHundredMetersWhileInsideBecomesOutsideWithoutTouchingTheEntryTime() {
        StoreProximityState state = enteredAt(NOON);

        assertEquals(LEFT, state.checkProximity(100.001, NOON.plusSeconds(5)).transition());
        assertFalse(state.isInside());
        assertEquals(NOON, state.getLastPhysicalEntryTime());
    }

    @Test
    void pairsAreIndependent() {
        StoreProximityState ataşehir = enteredAt(NOON);
        exit(ataşehir, NOON.plusSeconds(10));
        StoreProximityState novada = StoreProximityState.initial("courier-1", "Novada MMM Migros");

        // Ataşehir's recent entry does not make Novada's first entry an uncounted re-entry.
        assertEntry(novada.checkProximity(INSIDE, NOON.plusSeconds(20)), true, NOON.plusSeconds(20));
    }

    private static StoreProximityState enteredAt(Instant time) {
        StoreProximityState state = StoreProximityState.initial("courier-1", "store");
        state.checkProximity(INSIDE, time);
        return state;
    }

    private static void exit(StoreProximityState state, Instant time) {
        ProximityCheck check = state.checkProximity(OUTSIDE, time);
        assertEquals(LEFT, check.transition());
        assertTrue(check.entry().isEmpty());
        assertFalse(state.isInside());
    }

    private static void assertEntry(ProximityCheck check, boolean expectedCounted, Instant expectedTime) {
        assertEquals(ENTERED, check.transition());
        assertTrue(check.entry().isPresent(), "expected a physical entry");
        assertEquals(expectedCounted, check.entry().get().isCounted());
        assertEquals(expectedTime, check.entry().get().getEntryTime());
    }
}
