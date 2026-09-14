package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.PsychologistDutyDto;
import com.humanitarian.platform.dto.PsychologistDutyResponse;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets a psychologist go on and off duty (UX-2). {@code is_on_duty} gates
 * crisis routing entirely and until now could only be changed with SQL.
 * Going off duty does not touch cases already assigned.
 */
@Service
public class PsychologistDutyService {

    private static final Logger log = LoggerFactory.getLogger(PsychologistDutyService.class);

    private final PsychologistRepository psychologistRepository;
    private final PsychologicalRequestRepository psychologicalRequestRepository;
    private final UserService userService;

    public PsychologistDutyService(PsychologistRepository psychologistRepository,
                                   PsychologicalRequestRepository psychologicalRequestRepository,
                                   UserService userService) {
        this.psychologistRepository = psychologistRepository;
        this.psychologicalRequestRepository = psychologicalRequestRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public PsychologistDutyResponse getMyDuty() {
        User me = userService.getCurrentUser();
        return toResponse(me, myProfile(me));
    }

    @Transactional
    public PsychologistDutyResponse setMyDuty(PsychologistDutyDto request) {
        if (request == null || request.getOnDuty() == null) {
            throw new BusinessException("onDuty is required.");
        }
        User me = userService.getCurrentUser();
        Psychologist profile = myProfile(me);
        profile.setIsOnDuty(request.getOnDuty());
        Psychologist saved = psychologistRepository.save(profile);
        log.info("Psychologist {} (user {}) is now {} duty{}", saved.getId(), me.getId(),
                saved.getIsOnDuty() ? "on" : "off",
                Boolean.TRUE.equals(saved.getIsVerified()) ? "" : " (not yet verified: crisis routing skips them)");
        return toResponse(me, saved);
    }

    private Psychologist myProfile(User me) {
        return psychologistRepository.findByUserId(me.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Psychologist profile not found. Contact admin."));
    }

    private PsychologistDutyResponse toResponse(User me, Psychologist profile) {
        return PsychologistDutyResponse.builder()
                .userId(me.getId())
                .psychologistId(profile.getId())
                .onDuty(Boolean.TRUE.equals(profile.getIsOnDuty()))
                .verified(Boolean.TRUE.equals(profile.getIsVerified()))
                .openCases(psychologicalRequestRepository.countByAssignedPsychologistIdAndStatus(profile.getId(), "ASSIGNED"))
                .build();
    }
}
