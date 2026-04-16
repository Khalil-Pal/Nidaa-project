package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.HelpRequestDto;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.HelpRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HelpRequestService {

    @Autowired
    private HelpRequestRepository helpRequestRepository;

    @Autowired
    private UserService userService;

    @Transactional
    public HelpRequest createRequest(HelpRequestDto dto) {
        User currentUser = userService.getCurrentUser();

        // Normalize enum values to match PostgreSQL enum exactly
        String helpType    = mapHelpType(dto.getHelpType());
        String urgency     = mapUrgency(dto.getUrgencyLevel());

        int count = 1;
        if (dto.getPeopleCount() != null && dto.getPeopleCount() > 0) {
            count = dto.getPeopleCount();
        } else if (dto.getNumberOfPeople() != null && !dto.getNumberOfPeople().isBlank()) {
            try { count = Integer.parseInt(dto.getNumberOfPeople().trim().split("[^0-9]")[0]); }
            catch (Exception ignored) {}
        }

        String desc = (dto.getDescription() != null && !dto.getDescription().isBlank())
                ? dto.getDescription() : dto.getTitle();

        HelpRequest request = HelpRequest.builder()
                .beneficiaryId(currentUser.getId())
                .title(dto.getTitle().trim())
                .description(desc)
                .helpType(helpType)
                .urgencyLevel(urgency)
                .peopleCount(count)
                .hasChildren(dto.getHasChildren() != null ? dto.getHasChildren() : false)
                .hasElderly(dto.getHasElderly() != null ? dto.getHasElderly() : false)
                .hasDisabled(dto.getHasDisabled() != null ? dto.getHasDisabled() : false)
                .address(dto.getAddress())
                .status("PENDING")
                .build();

        return helpRequestRepository.save(request);
    }

    private String mapHelpType(String raw) {
        if (raw == null) return "OTHER";
        return switch (raw.toUpperCase().trim()) {
            case "MEDICAL"       -> "MEDICAL";
            case "FOOD"          -> "FOOD";
            case "SHELTER"       -> "SHELTER";
            case "WATER"         -> "WATER";
            case "CLOTHING"      -> "CLOTHING";
            case "PSYCHOLOGICAL" -> "PSYCHOLOGICAL";
            default              -> "OTHER";
        };
    }

    private String mapUrgency(String raw) {
        if (raw == null) return "MEDIUM";
        return switch (raw.toUpperCase().trim()) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH"     -> "HIGH";
            case "LOW"      -> "LOW";
            default         -> "MEDIUM";
        };
    }

    public List<HelpRequest> getAllRequests() {
        return helpRequestRepository.findAll();
    }

    public List<HelpRequest> getRequestsByStatus(String status) {
        return helpRequestRepository.findByStatus(status.toUpperCase());
    }

    public List<HelpRequest> getMyRequests() {
        User currentUser = userService.getCurrentUser();
        return helpRequestRepository.findByBeneficiaryId(currentUser.getId());
    }

    public HelpRequest getRequestById(Long id) {
        return helpRequestRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Help request not found: " + id));
    }

    @Transactional
    public HelpRequest updateStatus(Long id, String status) {
        HelpRequest request = getRequestById(id);
        request.setStatus(status.toUpperCase());
        if ("COMPLETED".equalsIgnoreCase(status)) request.setCompletedAt(LocalDateTime.now());
        if ("CANCELLED".equalsIgnoreCase(status)) request.setCancelledAt(LocalDateTime.now());
        return helpRequestRepository.save(request);
    }

    @Transactional
    public void deleteRequest(Long id) {
        HelpRequest request = getRequestById(id);
        User currentUser = userService.getCurrentUser();
        if (!request.getBeneficiaryId().equals(currentUser.getId())) {
            throw new RuntimeException("You can only delete your own requests");
        }
        helpRequestRepository.delete(request);
    }

    public List<HelpRequest> getPendingByPriority() {
        return helpRequestRepository.findByStatusOrderByPriorityScoreDesc("PENDING");
    }

    public List<HelpRequest> getRequestsByType(String helpType) {
        return helpRequestRepository.findByHelpType(mapHelpType(helpType));
    }
}