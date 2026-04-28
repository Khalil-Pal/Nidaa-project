package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.PsychologicalRequestDto;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PsychologicalRequestService {

    @Autowired private PsychologicalRequestRepository repo;
    @Autowired private UserService                    userService;
    @Autowired private JdbcTemplate                   jdbc;

    @Transactional
    public PsychologicalRequest createRequest(PsychologicalRequestDto dto) {
        User user = userService.getCurrentUser();
        PsychologicalRequest r = PsychologicalRequest.builder()
                .beneficiaryId(user.getId())
                .supportType(toSupportType(dto.getSupportType()))
                .category(toCategory(dto.getCategory()))
                .urgencyLevel(toUrgency(dto.getUrgencyLevel()))
                .preferredFormat(toFormat(dto.getPreferredFormat()))
                .description(dto.getDescription() != null ? dto.getDescription() : "")
                .isAnonymous(dto.getIsAnonymous() != null ? dto.getIsAnonymous() : false)
                .status("PENDING")
                .isCrisis(false)
                .build();
        return repo.save(r);
    }

    @Transactional
    public PsychologicalRequest acceptRequest(Long requestId) {
        User currentUser = userService.getCurrentUser();

        // Must store psychologist_id (PK of psychologists table), not user_id
        // psychological_requests.assigned_psychologist_id → FK to psychologists.psychologist_id
        Long psychologistId;
        try {
            psychologistId = jdbc.queryForObject(
                    "SELECT psychologist_id FROM psychologists WHERE user_id = ?",
                    Long.class, currentUser.getId());
        } catch (Exception e) {
            psychologistId = null;
        }

        if (psychologistId == null) {
            throw new ResourceNotFoundException("Psychologist profile not found. Contact admin.");
        }

        int updated = repo.assignPsychologist(requestId, psychologistId, "ASSIGNED", "PENDING");
        if (updated == 0) {
            throw new BusinessException("Request is no longer available or already assigned.");
        }
        return getRequestById(requestId);
    }

    private static final Map<String, Set<String>> VALID_TRANSITIONS = Map.of(
        "PENDING",   Set.of("ASSIGNED", "CANCELLED"),
        "ASSIGNED",  Set.of("COMPLETED", "CANCELLED"),
        "COMPLETED", Set.of(),
        "CANCELLED", Set.of()
    );

    @Transactional
    public PsychologicalRequest updateStatus(Long id, String status) {
        PsychologicalRequest request = getRequestById(id);
        User currentUser = userService.getCurrentUser();
        String role = currentUser.getRole().name().toLowerCase();

        // Ownership check
        boolean canUpdate = role.equals("admin")
            || (role.equals("beneficiary") && request.getBeneficiaryId().equals(currentUser.getId()))
            || role.equals("psychologist")
            || role.equals("volunteer")
            || role.equals("organization");

        if (!canUpdate) {
            throw new UnauthorizedException("You do not have permission to update this request.");
        }

        // Transition validation
        String current = request.getStatus();
        String next = status.toUpperCase();

        if (!VALID_TRANSITIONS.getOrDefault(current, Set.of()).contains(next)) {
            throw new BusinessException("Invalid status transition: cannot move from " + current + " to " + next);
        }

        repo.updateStatusNative(id, next);
        return getRequestById(id);
    }

    public List<PsychologicalRequest> getAllRequests() { return repo.findAll(); }

    public List<PsychologicalRequest> getPendingRequests() {
        return repo.findByStatusAndAssignedPsychologistIdIsNull("PENDING");
    }

    public List<PsychologicalRequest> getMyRequests() {
        return repo.findByBeneficiaryId(userService.getCurrentUser().getId());
    }

    public List<PsychologicalRequest> getMyAssignedRequests() {
        User user = userService.getCurrentUser();
        Long psychologistId;
        try {
            psychologistId = jdbc.queryForObject(
                    "SELECT psychologist_id FROM psychologists WHERE user_id = ?",
                    Long.class, user.getId());
        } catch (Exception e) {
            psychologistId = null;
        }
        if (psychologistId == null) return List.of();
        return repo.findByAssignedPsychologistId(psychologistId);
    }

    public PsychologicalRequest getRequestById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found: " + id));
    }

    private String toCategory(String v) {
        if (v == null) return "ANXIETY";
        String u = v.toUpperCase().trim().replace(" & ","_AND_").replace(" ","_").replace("-","_");
        return switch (u) {
            case "ANXIETY"                             -> "ANXIETY";
            case "DEPRESSION"                          -> "DEPRESSION";
            case "PTSD"                                -> "PTSD";
            case "GRIEF_AND_LOSS","GRIEF_LOSS","GRIEF" -> "GRIEF_AND_LOSS";
            case "DOMESTIC_VIOLENCE","VIOLENCE"        -> "DOMESTIC_VIOLENCE";
            case "CRISIS_SUPPORT","CRISIS"             -> "CRISIS_SUPPORT";
            default                                    -> "ANXIETY";
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
            case "AUDIO","AUDIO_CALL"    -> "AUDIO";
            case "VIDEO","VIDEO_SESSION" -> "VIDEO";
            default                      -> "CHAT";
        };
    }
}