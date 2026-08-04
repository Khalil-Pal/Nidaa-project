package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.ContactInfoResponse;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactInfoServiceTest {

    @Mock private HelpRequestRepository helpRequestRepository;
    @Mock private PsychologicalRequestRepository psychologicalRequestRepository;
    @Mock private UserRepository userRepository;
    @Mock private VolunteerRepository volunteerRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private PsychologistRepository psychologistRepository;
    @Mock private UserService userService;

    @InjectMocks private ContactInfoService service;

    @Test
    void unrelatedVolunteerCannotViewMaterialRequestContact() {
        HelpRequest request = HelpRequest.builder()
                .id(10L)
                .beneficiaryId(1L)
                .assignedVolunteerId(100L)
                .status("ASSIGNED")
                .build();
        User unrelated = user(3L, UserRole.VOLUNTEER, "Other Volunteer");
        Volunteer unrelatedProfile = Volunteer.builder()
                .id(101L)
                .user(unrelated)
                .build();
        when(helpRequestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(userService.getCurrentUser()).thenReturn(unrelated);
        when(volunteerRepository.findByUserId(3L)).thenReturn(Optional.of(unrelatedProfile));

        assertThrows(UnauthorizedException.class,
                () -> service.getHelpRequestContact(10L));

        verify(userRepository, never()).findById(1L);
    }

    @Test
    void assignedVolunteerCanViewMaterialRequesterContact() {
        HelpRequest request = HelpRequest.builder()
                .id(11L)
                .beneficiaryId(1L)
                .assignedVolunteerId(100L)
                .status("ASSIGNED")
                .build();
        User volunteer = user(2L, UserRole.VOLUNTEER, "Assigned Volunteer");
        User beneficiary = user(1L, UserRole.BENEFICIARY, "Request Owner");
        when(helpRequestRepository.findById(11L)).thenReturn(Optional.of(request));
        when(userService.getCurrentUser()).thenReturn(volunteer);
        when(volunteerRepository.findByUserId(2L)).thenReturn(Optional.of(
                Volunteer.builder().id(100L).user(volunteer).build()));
        when(userRepository.findById(1L)).thenReturn(Optional.of(beneficiary));

        ContactInfoResponse response = service.getHelpRequestContact(11L);

        assertEquals("Request Owner", response.getName());
        assertEquals("beneficiary@example.test", response.getEmail());
        assertEquals("BENEFICIARY", response.getContactRole());
        assertFalse(response.isAnonymous());
    }

    @Test
    void materialRequesterCanViewAssignedOrganizationContact() {
        HelpRequest request = HelpRequest.builder()
                .id(12L)
                .beneficiaryId(1L)
                .assignedOrganizationId(200L)
                .status("ASSIGNED")
                .build();
        User beneficiary = user(1L, UserRole.BENEFICIARY, "Request Owner");
        User organizationUser = user(4L, UserRole.ORGANIZATION, "Relief Center");
        when(helpRequestRepository.findById(12L)).thenReturn(Optional.of(request));
        when(userService.getCurrentUser()).thenReturn(beneficiary);
        when(organizationRepository.findById(200L)).thenReturn(Optional.of(
                Organization.builder().id(200L).user(organizationUser).build()));

        ContactInfoResponse response = service.getHelpRequestContact(12L);

        assertEquals("Relief Center", response.getName());
        assertEquals("organization@example.test", response.getEmail());
        assertEquals("ORGANIZATION", response.getContactRole());
    }

    @Test
    void assignedOrganizationCanViewMaterialRequesterContact() {
        HelpRequest request = HelpRequest.builder()
                .id(13L)
                .beneficiaryId(1L)
                .assignedOrganizationId(200L)
                .status("ASSIGNED")
                .build();
        User organization = user(4L, UserRole.ORGANIZATION, "Relief Center");
        User beneficiary = user(1L, UserRole.BENEFICIARY, "Request Owner");
        when(helpRequestRepository.findById(13L)).thenReturn(Optional.of(request));
        when(userService.getCurrentUser()).thenReturn(organization);
        when(organizationRepository.findByUserId(4L)).thenReturn(Optional.of(
                Organization.builder().id(200L).user(organization).build()));
        when(userRepository.findById(1L)).thenReturn(Optional.of(beneficiary));

        ContactInfoResponse response = service.getHelpRequestContact(13L);

        assertEquals("Request Owner", response.getName());
        assertEquals("beneficiary@example.test", response.getEmail());
        assertEquals("BENEFICIARY", response.getContactRole());
    }

    @Test
    void assignedPsychologistCanViewNonAnonymousRequesterContact() {
        PsychologicalRequest request = PsychologicalRequest.builder()
                .id(20L)
                .beneficiaryId(1L)
                .assignedPsychologistId(300L)
                .isAnonymous(false)
                .status("ASSIGNED")
                .build();
        User psychologist = user(5L, UserRole.PSYCHOLOGIST, "Assigned Psychologist");
        User beneficiary = user(1L, UserRole.BENEFICIARY, "Support Requester");
        when(psychologicalRequestRepository.findById(20L)).thenReturn(Optional.of(request));
        when(userService.getCurrentUser()).thenReturn(psychologist);
        when(psychologistRepository.findByUserId(5L)).thenReturn(Optional.of(
                Psychologist.builder().id(300L).user(psychologist).build()));
        when(userRepository.findById(1L)).thenReturn(Optional.of(beneficiary));

        ContactInfoResponse response = service.getPsychologicalRequestContact(20L);

        assertEquals("Support Requester", response.getName());
        assertEquals("beneficiary@example.test", response.getEmail());
        assertFalse(response.isAnonymous());
    }

    @Test
    void anonymousPsychologicalRequestNeverRevealsBeneficiaryContact() {
        PsychologicalRequest request = PsychologicalRequest.builder()
                .id(21L)
                .beneficiaryId(1L)
                .assignedPsychologistId(300L)
                .isAnonymous(true)
                .status("ASSIGNED")
                .build();
        User psychologist = user(5L, UserRole.PSYCHOLOGIST, "Assigned Psychologist");
        when(psychologicalRequestRepository.findById(21L)).thenReturn(Optional.of(request));
        when(userService.getCurrentUser()).thenReturn(psychologist);
        when(psychologistRepository.findByUserId(5L)).thenReturn(Optional.of(
                Psychologist.builder().id(300L).user(psychologist).build()));

        ContactInfoResponse response = service.getPsychologicalRequestContact(21L);

        assertTrue(response.isAnonymous());
        assertEquals("Anonymous beneficiary", response.getName());
        assertNull(response.getEmail());
        assertNull(response.getPhone());
        verify(userRepository, never()).findById(1L);
    }

    @Test
    void psychologicalRequesterCanViewAssignedPsychologistContact() {
        PsychologicalRequest request = PsychologicalRequest.builder()
                .id(22L)
                .beneficiaryId(1L)
                .assignedPsychologistId(300L)
                .isAnonymous(true)
                .status("ASSIGNED")
                .build();
        User beneficiary = user(1L, UserRole.BENEFICIARY, "Support Requester");
        User psychologist = user(5L, UserRole.PSYCHOLOGIST, "Assigned Psychologist");
        when(psychologicalRequestRepository.findById(22L)).thenReturn(Optional.of(request));
        when(userService.getCurrentUser()).thenReturn(beneficiary);
        when(psychologistRepository.findById(300L)).thenReturn(Optional.of(
                Psychologist.builder().id(300L).user(psychologist).build()));

        ContactInfoResponse response = service.getPsychologicalRequestContact(22L);

        assertEquals("Assigned Psychologist", response.getName());
        assertEquals("psychologist@example.test", response.getEmail());
        assertEquals("PSYCHOLOGIST", response.getContactRole());
        assertFalse(response.isAnonymous());
    }

    @Test
    void unrelatedPsychologistCannotViewPsychologicalRequesterContact() {
        PsychologicalRequest request = PsychologicalRequest.builder()
                .id(23L)
                .beneficiaryId(1L)
                .assignedPsychologistId(300L)
                .isAnonymous(false)
                .status("ASSIGNED")
                .build();
        User unrelated = user(6L, UserRole.PSYCHOLOGIST, "Other Psychologist");
        when(psychologicalRequestRepository.findById(23L)).thenReturn(Optional.of(request));
        when(userService.getCurrentUser()).thenReturn(unrelated);
        when(psychologistRepository.findByUserId(6L)).thenReturn(Optional.of(
                Psychologist.builder().id(301L).user(unrelated).build()));

        assertThrows(UnauthorizedException.class,
                () -> service.getPsychologicalRequestContact(23L));

        verify(userRepository, never()).findById(1L);
    }

    private User user(Long id, UserRole role, String fullName) {
        return User.builder()
                .id(id)
                .role(role)
                .fullName(fullName)
                .email(role.name().toLowerCase() + "@example.test")
                .phone("+10000000000")
                .build();
    }
}
