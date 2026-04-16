package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.PsychologicalRequestDto;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PsychologicalRequestService {

    @Autowired
    private PsychologicalRequestRepository psychologicalRequestRepository;

    @Autowired
    private UserService userService;

    @Transactional
    public PsychologicalRequest createRequest(PsychologicalRequestDto dto) {
        User currentUser = userService.getCurrentUser();

        PsychologicalRequest request = PsychologicalRequest.builder()
                .beneficiaryId(currentUser.getId())
                .supportType(toSupportType(dto.getSupportType()))
                .category(toCategory(dto.getCategory()))
                .urgencyLevel(toUrgency(dto.getUrgencyLevel()))
                .preferredFormat(toFormat(dto.getPreferredFormat()))
                .description(dto.getDescription() != null ? dto.getDescription() : "")
                .isAnonymous(dto.getIsAnonymous() != null ? dto.getIsAnonymous() : false)
                .status("PENDING")
                .isCrisis(false)
                .build();

        return psychologicalRequestRepository.save(request);
    }

    // These methods normalize frontend values to exact PostgreSQL enum values
    private String toCategory(String v) {
        if (v == null) return "ANXIETY";
        String u = v.toUpperCase().trim()
                .replace(" & ", "_AND_")
                .replace(" ", "_")
                .replace("-", "_");
        return switch (u) {
            case "ANXIETY"                        -> "ANXIETY";
            case "DEPRESSION"                     -> "DEPRESSION";
            case "PTSD"                           -> "PTSD";
            case "GRIEF_AND_LOSS", "GRIEF_LOSS",
                 "GRIEF"                          -> "GRIEF_AND_LOSS";
            case "DOMESTIC_VIOLENCE", "VIOLENCE"  -> "DOMESTIC_VIOLENCE";
            case "CRISIS_SUPPORT", "CRISIS"       -> "CRISIS_SUPPORT";
            default                               -> "ANXIETY";
        };
    }

    private String toSupportType(String v) {
        if (v == null) return "INDIVIDUAL";
        return switch (v.toUpperCase().trim()) {
            case "GROUP"  -> "GROUP";
            case "CRISIS" -> "CRISIS";
            default       -> "INDIVIDUAL";
        };
    }

    private String toUrgency(String v) {
        if (v == null) return "MEDIUM";
        return switch (v.toUpperCase().trim()) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH"     -> "HIGH";
            case "LOW"      -> "LOW";
            default         -> "MEDIUM";
        };
    }

    private String toFormat(String v) {
        if (v == null) return "CHAT";
        return switch (v.toUpperCase().trim()) {
            case "AUDIO", "AUDIO_CALL" -> "AUDIO";
            case "VIDEO", "VIDEO_SESSION" -> "VIDEO";
            default -> "CHAT";
        };
    }

    public List<PsychologicalRequest> getAllRequests() {
        return psychologicalRequestRepository.findAll();
    }

    public List<PsychologicalRequest> getPendingRequests() {
        return psychologicalRequestRepository
                .findByStatusAndAssignedPsychologistIdIsNull("PENDING");
    }

    public List<PsychologicalRequest> getMyRequests() {
        User currentUser = userService.getCurrentUser();
        return psychologicalRequestRepository.findByBeneficiaryId(currentUser.getId());
    }

    public List<PsychologicalRequest> getMyAssignedRequests() {
        User currentUser = userService.getCurrentUser();
        return psychologicalRequestRepository.findByAssignedPsychologistId(currentUser.getId());
    }

    public PsychologicalRequest getRequestById(Long id) {
        return psychologicalRequestRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Request not found: " + id));
    }

    @Transactional
    public PsychologicalRequest acceptRequest(Long requestId) {
        User currentUser = userService.getCurrentUser();
        PsychologicalRequest request = getRequestById(requestId);
        if (!"PENDING".equals(request.getStatus())) {
            throw new RuntimeException("This request is no longer available.");
        }
        request.setAssignedPsychologistId(currentUser.getId());
        request.setStatus("ASSIGNED");
        return psychologicalRequestRepository.save(request);
    }

    @Transactional
    public PsychologicalRequest updateStatus(Long id, String status) {
        PsychologicalRequest request = getRequestById(id);
        request.setStatus(status.toUpperCase());
        return psychologicalRequestRepository.save(request);
    }
}