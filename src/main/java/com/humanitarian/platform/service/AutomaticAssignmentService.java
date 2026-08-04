package com.humanitarian.platform.service;

import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
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
    private final OrganizationRepository organizationRepository;
    private final PsychologistRepository psychologistRepository;
    private final AssignmentRepository assignmentRepository;
    private final GeoMatchingService geoMatchingService;
    private final ProviderResourceService providerResourceService;

    public AutomaticAssignmentService(HelpRequestRepository helpRequestRepository,
                                      PsychologicalRequestRepository psychologicalRequestRepository,
                                      VolunteerRepository volunteerRepository,
                                      OrganizationRepository organizationRepository,
                                      PsychologistRepository psychologistRepository,
                                      AssignmentRepository assignmentRepository,
                                      GeoMatchingService geoMatchingService,
                                      ProviderResourceService providerResourceService) {
        this.helpRequestRepository = helpRequestRepository;
        this.psychologicalRequestRepository = psychologicalRequestRepository;
        this.volunteerRepository = volunteerRepository;
        this.organizationRepository = organizationRepository;
        this.psychologistRepository = psychologistRepository;
        this.assignmentRepository = assignmentRepository;
        this.geoMatchingService = geoMatchingService;
        this.providerResourceService = providerResourceService;
    }

    @Transactional
    public boolean assignNearestProvider(HelpRequest request) {
        if (request == null || request.getId() == null
                || request.getLatitude() == null || request.getLongitude() == null) {
            return false;
        }

        var eligibleUserIds = providerResourceService
                .findEligibleProviderUserIds(request.getHelpType());
        List<Volunteer> resourceMatchedVolunteers = volunteerRepository.findByIsAvailableTrue()
                .stream()
                .filter(volunteer -> volunteer.getUser() != null)
                .filter(volunteer -> eligibleUserIds.contains(volunteer.getUser().getId()))
                .toList();
        List<Organization> resourceMatchedOrganizations = organizationRepository.findByIsAvailableTrue()
                .stream()
                .filter(organization -> organization.getUser() != null)
                .filter(organization -> eligibleUserIds.contains(organization.getUser().getId()))
                .toList();
        List<GeoMatchingService.ProviderMatch> candidates =
                geoMatchingService.rankProvidersByDistance(
                        request, resourceMatchedVolunteers, resourceMatchedOrganizations);

        for (GeoMatchingService.ProviderMatch candidate : candidates) {
            if (!claim(candidate)) {
                continue;
            }

            int assigned = assignRequest(request.getId(), candidate);
            if (assigned == 0) {
                release(candidate);
                return false;
            }

            assignmentRepository.save(Assignment.builder()
                    .requestId(request.getId())
                    .requestType("HELP_REQUEST")
                    .volunteerId(candidate.providerType() == UserRole.VOLUNTEER
                            ? candidate.providerId() : null)
                    .organizationId(candidate.providerType() == UserRole.ORGANIZATION
                            ? candidate.providerId() : null)
                    .assignmentSource("AUTO_GEO")
                    .status("ASSIGNED")
                    .assignedAt(LocalDateTime.now())
                    .notes(String.format(Locale.ROOT,
                            "Automatically matched to nearest available %s (%.2f km)",
                            candidate.providerType().name().toLowerCase(Locale.ROOT),
                            candidate.distanceKm()))
                    .build());
            return true;
        }

        return false;
    }

    private boolean claim(GeoMatchingService.ProviderMatch candidate) {
        if (candidate.providerType() == UserRole.VOLUNTEER) {
            return volunteerRepository.claimIfAvailable(candidate.providerId()) == 1;
        }
        return organizationRepository.claimIfAvailable(candidate.providerId()) == 1;
    }

    private int assignRequest(Long requestId, GeoMatchingService.ProviderMatch candidate) {
        if (candidate.providerType() == UserRole.VOLUNTEER) {
            return helpRequestRepository.assignVolunteer(
                    requestId, candidate.providerId(), "ASSIGNED", "PENDING");
        }
        return helpRequestRepository.assignOrganization(
                requestId, candidate.providerId(), "ASSIGNED", "PENDING");
    }

    private void release(GeoMatchingService.ProviderMatch candidate) {
        if (candidate.providerType() == UserRole.VOLUNTEER) {
            volunteerRepository.release(candidate.providerId());
        } else {
            organizationRepository.release(candidate.providerId());
        }
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
