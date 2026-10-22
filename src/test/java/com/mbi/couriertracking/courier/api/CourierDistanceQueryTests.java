package com.mbi.couriertracking.courier.api;

import com.mbi.couriertracking.common.geo.DistanceCalculatorStrategy;
import com.mbi.couriertracking.courier.application.CourierService;
import com.mbi.couriertracking.courier.persistence.CourierLocationMapperImpl;
import com.mbi.couriertracking.courier.persistence.CourierLocationRepository;
import com.mbi.couriertracking.courier.persistence.CourierRepository;
import com.mbi.couriertracking.store.application.StoreEntryDetector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CourierController.class)
@Import({CourierService.class, CourierMapperImpl.class, CourierLocationMapperImpl.class})
class CourierDistanceQueryTests {

    @MockitoBean
    private CourierLocationRepository locationRepository;

    @MockitoBean
    private CourierRepository courierRepository;

    @MockitoBean
    private DistanceCalculatorStrategy distanceCalculator;

    @MockitoBean
    private StoreEntryDetector storeEntryDetector;

    @Autowired
    private MockMvc mvc;

    @Test
    void unknownCourierReturnsNotFoundFromRealService() throws Exception {
        mvc.perform(get("/api/v1/couriers/distance").param("courierId", "courier-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
