package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.ProviderAvailabilityDto;
import com.humanitarian.platform.dto.ProviderAvailabilityResponse;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderAvailabilityService {

    private final VolunteerRepository volunteerRepository;
    private final OrganizationRepository organizationRepository;
    private final AssignmentRepository assignmentRepository;
    private final UserService userService;

    public ProviderAvailabilityService(VolunteerRepository volunteerRepository,
                                       OrganizationRepository organizationRepository,
                                       AssignmentRepository assignmentRepository,
                                       UserService userService) {
        this.volunteerRepository = volunteerRepository;
        this.organizationRepository = organizationRepository;
        this.assignmentRepository = assignmentRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public ProviderAvailabilityResponse getMyAvailability() {
        User currentUser = requireProvider();
        return loadAvailability(currentUser);
    }

    @Transactional
    public ProviderAvailabilityResponse setMyAvailability(ProviderAvailabilityDto request) {
        User currentUser = requireProvider();
        if (request == null || request.getAvailable() == null) {
            throw new BusinessException("Availability is required.");
        }

        boolean available = request.getAvailable();
        int updated = currentUser.getRole() == UserRole.VOLUNTEER
                ? volunteerRepository.setManualAvailability(currentUser.getId(), available)
                : organizationRepository.setManualAvailability(currentUser.getId(), available);
        if (updated == 0) {
            throw new ResourceNotFoundException(
                    providerLabel(currentUser.getRole()) + " profile not found.");
        }

        return loadAvailability(currentUser);
    }

    private ProviderAvailabilityResponse loadAvailability(User currentUser) {
        if (currentUser.getRole() == UserRole.VOLUNTEER) {
            Volunteer volunteer = volunteerRepository.findByUserId(currentUser.getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Volunteer profile not found."));
            return ProviderAvailabilityResponse.builder()
                    .userId(currentUser.getId())
                    .providerId(volunteer.getId())
                    .role(currentUser.getRole())
                    .available(volunteer.getIsAvailable())
                    .availabilityPreference(volunteer.getAvailabilityPreference())
                    .activeAssignmentCount(assignmentRepository
                            .countByVolunteerIdAndStatus(volunteer.getId(), "ASSIGNED"))
                    .build();
        }

        Organization organization = organizationRepository.findByUserId(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Organization profile not found."));
        return ProviderAvailabilityResponse.builder()
                .userId(currentUser.getId())
                .providerId(organization.getId())
                .role(currentUser.getRole())
                .available(organization.getIsAvailable())
                .availabilityPreference(organization.getAvailabilityPreference())
                .activeAssignmentCount(assignmentRepository
                        .countByOrganizationIdAndStatus(organization.getId(), "ASSIGNED"))
                .build();
    }

    private User requireProvider() {
        User currentUser = userService.getCurrentUser();
        if (currentUser.getRole() != UserRole.VOLUNTEER
                && currentUser.getRole() != UserRole.ORGANIZATION) {
            throw new UnauthorizedException(
                    "Only volunteers and organizations can manage provider availability.");
        }
        return currentUser;
    }

    private String providerLabel(UserRole role) {
        return role == UserRole.VOLUNTEER ? "Volunteer" : "Organization";
    }
}
