package com.mbi.couriertracking.store.domain;

import java.util.Optional;

/**
 * Outcome of checking one location report against a courier–store pair: how the inside/outside
 * state moved and, on an entry, the {@link StoreEntry} to record.
 */
public record ProximityCheck(Transition transition, Optional<StoreEntry> entry) {

    public enum Transition {ENTERED, LEFT, UNCHANGED}

    // Immutable, so the two entry-less outcomes are shared instead of allocated per check.
    private static final ProximityCheck LEFT = new ProximityCheck(Transition.LEFT, Optional.empty());
    private static final ProximityCheck UNCHANGED = new ProximityCheck(Transition.UNCHANGED, Optional.empty());

    static ProximityCheck entered(StoreEntry entry) {
        return new ProximityCheck(Transition.ENTERED, Optional.of(entry));
    }

    static ProximityCheck left() {
        return LEFT;
    }

    static ProximityCheck unchanged() {
        return UNCHANGED;
    }

    /** True when the pair state moved between inside and outside and therefore needs saving. */
    public boolean changedState() {
        return transition != Transition.UNCHANGED;
    }
}
