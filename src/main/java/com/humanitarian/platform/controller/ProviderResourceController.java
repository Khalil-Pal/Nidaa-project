package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.ProviderResourceDto;
import com.humanitarian.platform.dto.ProviderResourceResponse;
import com.humanitarian.platform.service.ProviderResourceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/provider-resources")
public class ProviderResourceController {

    private final ProviderResourceService providerResourceService;

    public ProviderResourceController(ProviderResourceService providerResourceService) {
        this.providerResourceService = providerResourceService;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<ProviderResourceResponse>>> getMyResources() {
        return ResponseEntity.ok(ApiResponse.success(
                "Provider resources retrieved", providerResourceService.getMyResources()));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<ProviderResourceResponse>> upsertResource(
            @Valid @RequestBody ProviderResourceDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Provider resource saved", providerResourceService.upsertResource(request)));
    }

    @DeleteMapping("/{helpType}")
    public ResponseEntity<ApiResponse<Void>> deleteResource(@PathVariable String helpType) {
        providerResourceService.deleteResource(helpType);
        return ResponseEntity.ok(ApiResponse.success("Provider resource deleted", null));
    }
}
