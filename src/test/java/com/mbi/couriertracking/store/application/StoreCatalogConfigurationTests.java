package com.mbi.couriertracking.store.application;

import com.mbi.couriertracking.common.geo.GeoLocation;
import com.mbi.couriertracking.store.domain.Store;
import com.mbi.couriertracking.store.domain.StoreCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Loads the real stores.json without a Spring context. */
class StoreCatalogConfigurationTests {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void loadsTheFiveSuppliedStoresWithExactNamesAndCoordinates() throws IOException {
        StoreCatalog catalog = configurationFor(new ClassPathResource("stores.json")).storeCatalog(jsonMapper);

        assertEquals(List.of(
                new Store("Ataşehir MMM Migros", new GeoLocation(40.9923307, 29.1244229)),
                new Store("Novada MMM Migros", new GeoLocation(40.986106, 29.1161293)),
                new Store("Beylikdüzü 5M Migros", new GeoLocation(41.0066851, 28.6552262)),
                new Store("Ortaköy MMM Migros", new GeoLocation(41.055783, 29.0210292)),
                new Store("Caddebostan MMM Migros", new GeoLocation(40.9632463, 29.0630908))
        ), catalog.stores());
    }

    @Test
    void missingFileFailsLoading() {
        StoreCatalogConfiguration configuration = configurationFor(new ClassPathResource("missing-stores.json"));

        assertThrows(IOException.class, () -> configuration.storeCatalog(jsonMapper));
    }

    @Test
    void invalidCoordinatesFailLoading() {
        StoreCatalogConfiguration configuration = configurationFor(new ByteArrayResource(
                "[{\"name\":\"Broken\",\"lat\":91,\"lng\":29}]".getBytes(StandardCharsets.UTF_8)));

        assertThrows(IllegalArgumentException.class, () -> configuration.storeCatalog(jsonMapper));
    }

    /** The stores file is a {@code @Value}-injected field, so tests set it directly. */
    private static StoreCatalogConfiguration configurationFor(Resource storesFile) {
        StoreCatalogConfiguration configuration = new StoreCatalogConfiguration();
        ReflectionTestUtils.setField(configuration, "storesFile", storesFile);
        return configuration;
    }
}
