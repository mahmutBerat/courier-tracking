package com.mbi.couriertracking.common.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI courierTrackingOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Courier Tracking API")
                .version("v1")
                .description("Register a courier, list couriers, then submit locations. "
                        + "Location submission accumulates distance; query the persisted total in meters. "
                        + "Store-entry processing is not implemented yet."));
    }
}
