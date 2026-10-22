package com.mbi.couriertracking.store.application;

import com.mbi.couriertracking.common.geo.GeoLocation;
import com.mbi.couriertracking.store.domain.Store;
import com.mbi.couriertracking.store.domain.StoreCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Loads the supplied stores file once; a missing or invalid file fails startup.
 */
@Configuration
public class StoreCatalogConfiguration {

    @Value("${courier-tracking.stores-file:classpath:stores.json}")
    private Resource storesFile;

    @Bean
    StoreCatalog storeCatalog(JsonMapper jsonMapper) throws IOException {
        // load from file
        try (InputStream content = storesFile.getInputStream()) {
            List<StoreDefinitionDTO> definitions = jsonMapper.readValue(content, new TypeReference<>() {
            });

            List<Store> stores = definitions.stream()
                    .map(dto -> new Store(dto.name(), new GeoLocation(dto.lat(), dto.lng())))
                    .toList();

            return new StoreCatalog(stores);
        }
    }

    record StoreDefinitionDTO(String name, double lat, double lng) {
    }
}
