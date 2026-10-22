package com.mbi.couriertracking.store.domain;

import com.mbi.couriertracking.common.geo.GeoLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoreCatalogTests {

    private static final Store ATASEHIR = new Store("Ataşehir MMM Migros", new GeoLocation(40.9923307, 29.1244229));
    private static final Store NOVADA = new Store("Novada MMM Migros", new GeoLocation(40.986106, 29.1161293));

    @Test
    void keepsSuppliedStoresInOrder() {
        StoreCatalog catalog = new StoreCatalog(List.of(ATASEHIR, NOVADA));

        assertEquals(List.of(ATASEHIR, NOVADA), catalog.stores());
    }

    @Test
    void rejectsEmptyCatalog() {
        assertThrows(IllegalArgumentException.class, () -> new StoreCatalog(List.of()));
    }

    @Test
    void storeRequiresNameAndLocation() {
        assertThrows(IllegalArgumentException.class, () -> new Store(" ", ATASEHIR.location()));
        assertThrows(IllegalArgumentException.class, () -> new Store("store", null));
    }
}
