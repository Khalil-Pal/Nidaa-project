package com.humanitarian.platform.service;

import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class AutomaticAssignmentService {

    private static final Logger logger = LoggerFactory.getLogger(AutomaticAssignmentService.class);

    private final HelpRequestRepository helpRequestRepository;
    private final PsychologicalRequestRepository psychologicalRequestRepository;
    private final VolunteerRepository volunteerRepository;
    private final PsychologistRepository psychologistRepository;
    private final AssignmentRepository assignmentRepository;
    private final GeoMatchingService geoMatchingService;

    public AutomaticAssignmentService(HelpRequestRepository helpRequestRepository,
                                      PsychologicalRequestRepository psychologicalRequestRepository,
                                      VolunteerRepository volunteerRepository,
                                      PsychologistRepository psychologistRepository,
                                      AssignmentRepository assignmentRepository,
                                      GeoMatchingService geoMatchingService) {
        this.helpRequestRepository = helpRequestRepository;
        this.psychologicalRequestRepository = psychologicalRequestRepository;
        this.volunteerRepository = volunteerRepository;
        this.psychologistRepository = psychologistRepository;
        this.assignmentRepository = assignmentRepository;
        this.geoMatchingService = geoMatchingService;
    }

    @Transactional
    public boolean assignNearestVolunteer(HelpRequest request) {
        if (request == null || request.getId() == null
                || request.getLatitude() == null || request.getLongitude() == null) {
            return false;
        }

        List<Volunteer> candidates = geoMatchingService.rankByDistance(
                request, volunteerRepository.findByIsAvailableTrue());

        for (Volunteer volunteer : candidates) {
            if (volunteerRepository.claimIfAvailable(volunteer.getId()) == 0) {
                continue;
            }

            int assigned = helpRequestRepository.assignVolunteer(
                    request.getId(), volunteer.getId(), "ASSIGNED", "PENDING");
            if (assigned == 0) {
                volunteerRepository.release(volunteer.getId());
                return false;
            }

            double distance = geoMatchingService.haversine(
                    request.getLatitude(), request.getLongitude(),
                    volunteer.getLatitude(), volunteer.getLongitude());

            assignmentRepository.save(Assignment.builder()
                    .requestId(request.getId())
                    .requestType("HELP_REQUEST")
                    .volunteerId(volunteer.getId())
                    .assignmentSource("AUTO_GEO")
                    .status("ASSIGNED")
                    .assignedAt(LocalDateTime.now())
                    .notes(String.format(Locale.ROOT,
                            "Automatically matched to nearest available volunteer (%.2f km)", distance))
                    .build());
            return true;
        }

        return false;
    }

    @Transactional
    public boolean routeCrisisRequest(PsychologicalRequest request) {
        if (request == null || request.getId() == null || !Boolean.TRUE.equals(request.getIsCrisis())) {
            return false;
        }

        List<Psychologist> onDuty = psychologistRepository.findByIsVerifiedTrueAndIsOnDutyTrue()
                .stream()
                .sorted(Comparator
                        .comparingLong((Psychologist psychologist) ->
                                psychologicalRequestRepository.countByAssignedPsychologistIdAndStatus(
                                        psychologist.getId(), "ASSIGNED"))
                        .thenComparing(
                                Psychologist::getRating,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(
                                Psychologist::getExperienceYears,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Psychologist::getId))
                .toList();

        for (Psychologist psychologist : onDuty) {
            int assigned = psychologicalRequestRepository.assignPsychologist(
                    request.getId(), psychologist.getId(), "ASSIGNED", "PENDING");
            if (assigned == 0) {
                return false;
            }

            assignmentRepository.save(Assignment.builder()
                    .psychologicalRequestId(request.getId())
                    .requestType("PSYCHOLOGICAL_REQUEST")
                    .psychologistId(psychologist.getId())
                    .assignmentSource("AUTO_CRISIS")
                    .status("ASSIGNED")
                    .assignedAt(LocalDateTime.now())
                    .notes("Automatically routed to a verified on-duty psychologist")
                    .build());
            return true;
        }

        logger.warn("Crisis request {} remains pending: no verified on-duty psychologist is available",
                request.getId());
        return false;
    }
}
