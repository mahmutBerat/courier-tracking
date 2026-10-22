package com.mbi.couriertracking.courier.api;

import com.mbi.couriertracking.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiDocumentationIntegrationTests {
    @Autowired
    private MockMvc mvc;

    @Test
    void generatedDocumentIncludesAllEndpointsAndCurrentContracts() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Courier Tracking API"))
                .andExpect(jsonPath("$.paths['/api/v1/couriers'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/couriers'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/couriers'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/couriers/locations'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/couriers/locations'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/couriers/distance'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/couriers/distance'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/couriers/distance'].get.responses['501']").doesNotExist())
                .andExpect(jsonPath("$.paths['/actuator/health'].get").exists())
                .andExpect(jsonPath("$.components.schemas.CreateCourierRequest.properties.courierId.example").value("courier-1"))
                .andExpect(jsonPath("$.components.schemas.CourierLocationInput.properties.time.example").value("2026-10-21T12:00:00Z"));
    }

    @Test
    void swaggerUiAndConfigurationAreServed() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
        mvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
    }

    @Test
    void yamlDocumentIsAvailable() throws Exception {
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/v1/couriers/locations:")));
    }
}
