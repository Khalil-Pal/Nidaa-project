package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.PsychologistDutyDto;
import com.humanitarian.platform.dto.PsychologistDutyResponse;
import com.humanitarian.platform.service.PsychologistDutyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/psychologists")
@PreAuthorize("hasRole('PSYCHOLOGIST')")
public class PsychologistController {

    private final PsychologistDutyService psychologistDutyService;

    public PsychologistController(PsychologistDutyService psychologistDutyService) {
        this.psychologistDutyService = psychologistDutyService;
    }

    @GetMapping("/me/duty")
    public ResponseEntity<ApiResponse<PsychologistDutyResponse>> getMyDuty() {
        return ResponseEntity.ok(ApiResponse.success("Duty status retrieved", psychologistDutyService.getMyDuty()));
    }

    @PutMapping("/me/duty")
    public ResponseEntity<ApiResponse<PsychologistDutyResponse>> setMyDuty(@Valid @RequestBody PsychologistDutyDto request) {
        return ResponseEntity.ok(ApiResponse.success("Duty status updated", psychologistDutyService.setMyDuty(request)));
    }
}
