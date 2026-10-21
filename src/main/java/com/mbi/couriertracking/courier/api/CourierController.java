package com.mbi.couriertracking.courier.api;

import com.mbi.couriertracking.courier.application.CourierService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/couriers")
public class CourierController {

    private final CourierService courierService;
    private final CourierMapper courierMapper;

    @PostMapping(value = "/locations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitLocation(@Valid @RequestBody CourierLocationInput input) {
        courierService.submitLocation(input);
    }

    @GetMapping("/distance")
    public TotalDistanceResponse getTotalDistance(@RequestParam("courierId") @NotBlank String courierId) {
        double distanceMeters = courierService.findTotalDistanceMeters(courierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Courier not found."));
        return courierMapper.toTotalDistanceResponse(courierId, distanceMeters);
    }
}
