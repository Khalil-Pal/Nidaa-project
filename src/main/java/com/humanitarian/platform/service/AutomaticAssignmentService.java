package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.ProviderCapacityAssessment;
import com.humanitarian.platform.dto.ProviderCapacityReservation;
import com.humanitarian.platform.dto.PsychologistCaseLoad;
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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
    private final NotificationService notifications;

    public AutomaticAssignmentService(HelpRequestRepository helpRequestRepository,
                                      PsychologicalRequestRepository psychologicalRequestRepository,
                                      VolunteerRepository volunteerRepository,
                                      OrganizationRepository organizationRepository,
                                      PsychologistRepository psychologistRepository,
                                      AssignmentRepository assignmentRepository,
                                      GeoMatchingService geoMatchingService,
                                      ProviderResourceService providerResourceService,
                                      NotificationService notifications) {
        this.helpRequestRepository = helpRequestRepository;
        this.psychologicalRequestRepository = psychologicalRequestRepository;
        this.volunteerRepository = volunteerRepository;
        this.organizationRepository = organizationRepository;
        this.psychologistRepository = psychologistRepository;
        this.assignmentRepository = assignmentRepository;
        this.geoMatchingService = geoMatchingService;
        this.providerResourceService = providerResourceService;
        this.notifications = notifications;
    }

    @Transactional
    public boolean assignNearestProvider(HelpRequest request) {
        if (request == null || request.getId() == null
                || request.getLatitude() == null || request.getLongitude() == null) {
            return false;
        }

        Map<Long, ProviderCapacityAssessment> eligibleProviders = providerResourceService
                .findEligibleProviderCapacityAssessments(
                        request.getHelpType(), request.getPeopleCount());
        var eligibleUserIds = eligibleProviders.keySet();
        // The provider who filed the request on someone's behalf is never a
        // candidate for delivering it (see HelpRequestService.assignToMe).
        Long filer = request.getFiledByUserId();
        List<Volunteer> resourceMatchedVolunteers = volunteerRepository.findByIsAvailableTrue()
                .stream()
                .filter(volunteer -> volunteer.getUser() != null)
                .filter(volunteer -> eligibleUserIds.contains(volunteer.getUser().getId()))
                .filter(volunteer -> !Objects.equals(volunteer.getUser().getId(), filer))
                .toList();
        List<Organization> resourceMatchedOrganizations = organizationRepository.findByIsAvailableTrue()
                .stream()
                .filter(organization -> organization.getUser() != null)
                .filter(organization -> eligibleUserIds.contains(organization.getUser().getId()))
                .filter(organization -> !Objects.equals(organization.getUser().getId(), filer))
                .toList();
        List<GeoMatchingService.ProviderMatch> candidates =
                geoMatchingService.rankProvidersByDistance(
                        request, resourceMatchedVolunteers, resourceMatchedOrganizations);
        if (candidates.isEmpty()) {
            logger.info("Request {} ({}, {} people) stays PENDING: {} providers have the resource, none available in range",
                    request.getId(), request.getHelpType(), request.getPeopleCount(), eligibleProviders.size());
            return false;
        }

        for (GeoMatchingService.ProviderMatch candidate : candidates) {
            if (!claim(candidate)) {
                logger.debug("Request {}: {} {} already claimed, trying next", request.getId(),
                        candidate.providerType(), candidate.providerId());
                continue;
            }

            var reservationResult = providerResourceService.reserveForAssignment(
                    candidate.userId(), request.getHelpType(), request.getPeopleCount());
            if (reservationResult.isEmpty()) {
                release(candidate);
                logger.debug("Request {}: {} {} lost its capacity before reservation, trying next", request.getId(),
                        candidate.providerType(), candidate.providerId());
                continue;
            }
            ProviderCapacityReservation reservation = reservationResult.get();

            int assigned = assignRequest(request.getId(), candidate);
            if (assigned == 0) {
                providerResourceService.restoreReservation(reservation);
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
                    .resourceUserId(reservation.hasNumericReservation()
                            ? reservation.userId() : null)
                    .resourceHelpType(reservation.hasNumericReservation()
                            ? reservation.helpType() : null)
                    .reservedCapacityAmount(reservation.reservedAmount())
                    .notes(String.format(Locale.ROOT,
                            "Automatically matched to nearest available %s (%.2f km)",
                            candidate.providerType().name().toLowerCase(Locale.ROOT),
                            candidate.distanceKm()))
                    .build());
            logger.info("Request {} auto-assigned to {} {} at {} km ({} of {} candidates tried)",
                    request.getId(), candidate.providerType(), candidate.providerId(),
                    String.format(Locale.ROOT, "%.2f", candidate.distanceKm()),
                    candidates.indexOf(candidate) + 1, candidates.size());
            // N-1: both sides of an automatic match are told; the filer too when someone filed it
            notifications.notify(candidate.userId(), "A request was matched to you",
                    "\"" + request.getTitle() + "\" was assigned to you as the nearest available provider.",
                    NotificationService.REF_HELP_REQUEST, request.getId());
            for (Long recipient : new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                    request.getBeneficiaryId(), request.getFiledByUserId()))) {
                notifications.notify(recipient, "Your request was matched",
                        "\"" + request.getTitle() + "\" was matched to the nearest available provider, who will be in touch.",
                        NotificationService.REF_HELP_REQUEST, request.getId());
            }
            return true;
        }

        logger.info("Request {} stays PENDING: all {} candidates were claimed or out of capacity first",
                request.getId(), candidates.size());
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

        // One grouped query before sorting; a query inside the comparator ran on every comparison (Q-1)
        Map<Long, Long> openCases = psychologicalRequestRepository.caseLoadByPsychologist("ASSIGNED").stream()
                .collect(Collectors.toMap(PsychologistCaseLoad::psychologistId, PsychologistCaseLoad::openCases));

        List<Psychologist> onDuty = psychologistRepository.findByIsVerifiedTrueAndIsOnDutyTrue()
                .stream()
                .sorted(Comparator
                        .comparingLong((Psychologist psychologist) -> openCases.getOrDefault(psychologist.getId(), 0L))
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
            logger.info("Crisis request {} routed to psychologist {} ({} open cases)",
                    request.getId(), psychologist.getId(), openCases.getOrDefault(psychologist.getId(), 0L));
            // N-1: the psychologist must see a crisis case at once; the person is told someone is coming
            notifications.notify(psychologist.getUser().getId(), "Crisis case routed to you",
                    "An urgent psychological support request was routed to you. Please open it now.",
                    NotificationService.REF_PSYCHOLOGICAL_REQUEST, request.getId());
            notifications.notify(request.getBeneficiaryId(), "A psychologist has been assigned to you",
                    "Your request was marked urgent and a psychologist on duty has been assigned. They will contact you shortly.",
                    NotificationService.REF_PSYCHOLOGICAL_REQUEST, request.getId());
            return true;
        }

        logger.warn("Crisis request {} remains pending: no verified on-duty psychologist is available",
                request.getId());
        return false;
    }
}
