package com.mbi.couriertracking.store.domain;

import java.util.Optional;

/**
 * Result of evaluating one report against the catalog: the visit opened or resumed by it, if any,
 * whether it opened a new visit (a logged entrance), and the store the courier is inside afterwards.
 */
public record StoreVisitResult(Optional<StoreVisit> entered, boolean entrance, Optional<String> currentStoreName) {

    private static final StoreVisitResult OUTSIDE = new StoreVisitResult(Optional.empty(), false, Optional.empty());

    /** No visit opened or resumed and the courier is inside no store. */
    public static StoreVisitResult outside() {
        return OUTSIDE;
    }
}
