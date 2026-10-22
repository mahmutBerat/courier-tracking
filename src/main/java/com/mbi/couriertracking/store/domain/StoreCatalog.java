package com.mbi.couriertracking.store.domain;

import java.util.List;

/** The fixed set of stores loaded at startup; never changes while the application runs. */
public record StoreCatalog(List<Store> stores) {

    public StoreCatalog {
        if (stores == null || stores.isEmpty()) {
            throw new IllegalArgumentException("Store catalog must contain at least one store.");
        }
        stores = List.copyOf(stores);
    }
}
