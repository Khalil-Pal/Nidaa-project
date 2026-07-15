package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.RankedRequestDTO;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.VolunteerRepository;
import com.humanitarian.platform.service.HelpRequestService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/help-requests")
public class HelpRequestV1Controller {

    private final HelpRequestService helpRequestService;
    private final VolunteerRepository volunteerRepository;

    public HelpRequestV1Controller(HelpRequestService helpRequestService,
                                   VolunteerRepository volunteerRepository) {
        this.helpRequestService = helpRequestService;
        this.volunteerRepository = volunteerRepository;
    }

    @GetMapping("/ranked")
    @PreAuthorize("hasRole('ADMIN') or hasRole('VOLUNTEER') or hasRole('ORGANIZATION')")
    public ResponseEntity<List<RankedRequestDTO>> getRanked() {
        List<Volunteer> available = volunteerRepository.findByIsAvailableTrue();
        return ResponseEntity.ok(helpRequestService.getRankedWithSuggestions(available));
    }
}
