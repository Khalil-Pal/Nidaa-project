package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.ProviderAvailabilityDto;
import com.humanitarian.platform.dto.ProviderAvailabilityResponse;
import com.humanitarian.platform.service.ProviderAvailabilityService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/provider-availability")
@PreAuthorize("hasAnyRole('VOLUNTEER', 'ORGANIZATION')")
public class ProviderAvailabilityController {

    private final ProviderAvailabilityService providerAvailabilityService;

    public ProviderAvailabilityController(
            ProviderAvailabilityService providerAvailabilityService) {
        this.providerAvailabilityService = providerAvailabilityService;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<ProviderAvailabilityResponse>> getMyAvailability() {
        return ResponseEntity.ok(ApiResponse.success(
                "Provider availability retrieved",
                providerAvailabilityService.getMyAvailability()));
    }

    @PutMapping("/me")
    public ResponseEntity<ApiResponse<ProviderAvailabilityResponse>> setMyAvailability(
            @Valid @RequestBody ProviderAvailabilityDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Provider availability updated",
                providerAvailabilityService.setMyAvailability(request)));
    }
}
