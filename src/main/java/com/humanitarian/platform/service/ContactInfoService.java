package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.ContactInfoResponse;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class ContactInfoService {

    private final HelpRequestRepository helpRequestRepository;
    private final PsychologicalRequestRepository psychologicalRequestRepository;
    private final UserRepository userRepository;
    private final VolunteerRepository volunteerRepository;
    private final OrganizationRepository organizationRepository;
    private final PsychologistRepository psychologistRepository;
    private final UserService userService;

    public ContactInfoService(HelpRequestRepository helpRequestRepository,
                              PsychologicalRequestRepository psychologicalRequestRepository,
                              UserRepository userRepository,
                              VolunteerRepository volunteerRepository,
                              OrganizationRepository organizationRepository,
                              PsychologistRepository psychologistRepository,
                              UserService userService) {
        this.helpRequestRepository = helpRequestRepository;
        this.psychologicalRequestRepository = psychologicalRequestRepository;
        this.userRepository = userRepository;
        this.volunteerRepository = volunteerRepository;
        this.organizationRepository = organizationRepository;
        this.psychologistRepository = psychologistRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public ContactInfoResponse getHelpRequestContact(Long requestId) {
        HelpRequest request = helpRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Help request not found: " + requestId));
        User currentUser = userService.getCurrentUser();

        if (Objects.equals(request.getBeneficiaryId(), currentUser.getId())) {
            return getAssignedMaterialProvider(request);
        }

        if (isAssignedVolunteer(currentUser, request)
                || isAssignedOrganization(currentUser, request)) {
            return contactFor(getUser(request.getBeneficiaryId()), UserRole.BENEFICIARY);
        }

        throw new UnauthorizedException(
                "Only the requester or assigned provider can view this contact information.");
    }

    @Transactional(readOnly = true)
    public ContactInfoResponse getPsychologicalRequestContact(Long requestId) {
        PsychologicalRequest request = psychologicalRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Psychological request not found: " + requestId));
        User currentUser = userService.getCurrentUser();

        if (Objects.equals(request.getBeneficiaryId(), currentUser.getId())) {
            if (request.getAssignedPsychologistId() == null) {
                throw new BusinessException(
                        "Contact information is available after a psychologist is assigned.");
            }
            User psychologist = psychologistRepository.findById(request.getAssignedPsychologistId())
                    .map(profile -> profile.getUser())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Assigned psychologist profile not found."));
            return contactFor(psychologist, UserRole.PSYCHOLOGIST);
        }

        if (!isAssignedPsychologist(currentUser, request)) {
            throw new UnauthorizedException(
                    "Only the requester or assigned psychologist can view this contact information.");
        }

        if (Boolean.TRUE.equals(request.getIsAnonymous())) {
            return ContactInfoResponse.builder()
                    .name("Anonymous beneficiary")
                    .contactRole(UserRole.BENEFICIARY.name())
                    .anonymous(true)
                    .message("The beneficiary chose to keep their identity and contact details private.")
                    .build();
        }

        return contactFor(getUser(request.getBeneficiaryId()), UserRole.BENEFICIARY);
    }

    private ContactInfoResponse getAssignedMaterialProvider(HelpRequest request) {
        if (request.getAssignedVolunteerId() != null) {
            User volunteer = volunteerRepository.findById(request.getAssignedVolunteerId())
                    .map(profile -> profile.getUser())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Assigned volunteer profile not found."));
            return contactFor(volunteer, UserRole.VOLUNTEER);
        }

        if (request.getAssignedOrganizationId() != null) {
            User organization = organizationRepository.findById(request.getAssignedOrganizationId())
                    .map(profile -> profile.getUser())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Assigned organization profile not found."));
            return contactFor(organization, UserRole.ORGANIZATION);
        }

        throw new BusinessException(
                "Contact information is available after a provider is assigned.");
    }

    private boolean isAssignedVolunteer(User currentUser, HelpRequest request) {
        if (currentUser.getRole() != UserRole.VOLUNTEER
                || request.getAssignedVolunteerId() == null) {
            return false;
        }
        return volunteerRepository.findByUserId(currentUser.getId())
                .map(profile -> Objects.equals(
                        profile.getId(), request.getAssignedVolunteerId()))
                .orElse(false);
    }

    private boolean isAssignedOrganization(User currentUser, HelpRequest request) {
        if (currentUser.getRole() != UserRole.ORGANIZATION
                || request.getAssignedOrganizationId() == null) {
            return false;
        }
        return organizationRepository.findByUserId(currentUser.getId())
                .map(profile -> Objects.equals(
                        profile.getId(), request.getAssignedOrganizationId()))
                .orElse(false);
    }

    private boolean isAssignedPsychologist(User currentUser, PsychologicalRequest request) {
        if (currentUser.getRole() != UserRole.PSYCHOLOGIST
                || request.getAssignedPsychologistId() == null) {
            return false;
        }
        return psychologistRepository.findByUserId(currentUser.getId())
                .map(profile -> Objects.equals(
                        profile.getId(), request.getAssignedPsychologistId()))
                .orElse(false);
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Contact user not found: " + userId));
    }

    private ContactInfoResponse contactFor(User user, UserRole role) {
        if (user == null) {
            throw new ResourceNotFoundException("Contact user not found.");
        }
        return ContactInfoResponse.builder()
                .name(user.getFullName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .contactRole(role.name())
                .build();
    }
}
