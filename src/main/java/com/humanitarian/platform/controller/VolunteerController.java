package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.VolunteerOccupationDto;
import com.humanitarian.platform.service.VolunteerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/volunteers")
public class VolunteerController {

    private final VolunteerService volunteerService;

    public VolunteerController(VolunteerService volunteerService) {
        this.volunteerService = volunteerService;
    }

    @GetMapping("/me/occupation")
    public ResponseEntity<ApiResponse<VolunteerOccupationDto>> getMyOccupation() {
        return ResponseEntity.ok(ApiResponse.success(
                "Occupation retrieved", volunteerService.getMyOccupation()));
    }

    @PutMapping("/me/occupation")
    public ResponseEntity<ApiResponse<VolunteerOccupationDto>> updateMyOccupation(
            @Valid @RequestBody VolunteerOccupationDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Occupation updated", volunteerService.updateMyOccupation(request)));
    }
}
