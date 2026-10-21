package com.mbi.couriertracking.courier.api;

import com.mbi.couriertracking.courier.application.CourierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Tag(name = "Couriers", description = "Register a courier before submitting locations.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/couriers")
public class CourierController {

    private final CourierService courierService;
    private final CourierMapper courierMapper;

    @Operation(summary = "Register a courier", description = "Registers an ID with zero distance.")
    @ApiResponse(responseCode = "201", description = "Courier registered", content = @Content)
    @ApiResponse(responseCode = "400", description = "Missing or blank courier ID", content = @Content)
    @ApiResponse(responseCode = "409", description = "Courier already exists", content = @Content)
    @ApiResponse(responseCode = "415", description = "JSON body required", content = @Content)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public void createCourier(@Valid @RequestBody CreateCourierRequest request) {
        courierService.createCourier(request.courierId());
    }

    @Operation(summary = "List registered couriers", description = "Returns all courier IDs in sorted order.")
    @ApiResponse(responseCode = "200", description = "Registered couriers")
    @GetMapping
    public List<CourierResponse> listCouriers() {
        return courierService.listCouriers().stream().map(courierMapper::toResponse).toList();
    }

    @Operation(summary = "Submit a courier location", description = "Stores a registered courier's location and updates distance when eligible.")
    @ApiResponse(responseCode = "204", description = "Location processed or exact duplicate ignored", content = @Content)
    @ApiResponse(responseCode = "400", description = "Invalid fields or coordinates", content = @Content)
    @ApiResponse(responseCode = "404", description = "Courier is not registered", content = @Content)
    @PostMapping(value = "/locations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitLocation(@Valid @RequestBody CourierLocationInput input) {
        courierService.submitLocation(input);
    }

    @Operation(summary = "Query total distance", description = "Returns the stored total in meters; zero before the first location.")
    @ApiResponse(responseCode = "200", description = "Persisted courier distance")
    @ApiResponse(responseCode = "400", description = "Missing or blank courierId", content = @Content)
    @ApiResponse(responseCode = "404", description = "Courier is not registered", content = @Content)
    @GetMapping("/distance")
    public TotalDistanceResponse getTotalDistance(@Parameter(description = "Registered courier ID", example = "courier-1") @RequestParam("courierId") @NotBlank String courierId) {
        double distanceMeters = courierService.findTotalDistanceMeters(courierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Courier not found."));
        return courierMapper.toTotalDistanceResponse(courierId, distanceMeters);
    }
}
