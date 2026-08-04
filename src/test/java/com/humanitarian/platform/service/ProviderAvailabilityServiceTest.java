package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.ProviderAvailabilityDto;
import com.humanitarian.platform.dto.ProviderAvailabilityResponse;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderAvailabilityServiceTest {

    @Mock private VolunteerRepository volunteerRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private AssignmentRepository assignmentRepository;
    @Mock private UserService userService;

    @InjectMocks private ProviderAvailabilityService service;

    @Test
    void volunteerCanSetAvailabilityFalse() {
        User volunteerUser = user(7L, UserRole.VOLUNTEER);
        Volunteer volunteer = Volunteer.builder()
                .id(17L)
                .user(volunteerUser)
                .isAvailable(false)
                .availabilityPreference(false)
                .build();
        when(userService.getCurrentUser()).thenReturn(volunteerUser);
        when(volunteerRepository.setManualAvailability(7L, false)).thenReturn(1);
        when(volunteerRepository.findByUserId(7L)).thenReturn(Optional.of(volunteer));
        when(assignmentRepository.countByVolunteerIdAndStatus(17L, "ASSIGNED"))
                .thenReturn(0L);

        ProviderAvailabilityResponse response =
                service.setMyAvailability(request(false));

        assertFalse(response.getAvailable());
        assertFalse(response.getAvailabilityPreference());
        assertEquals(UserRole.VOLUNTEER, response.getRole());
        verify(volunteerRepository).setManualAvailability(7L, false);
        verify(organizationRepository, never()).setManualAvailability(any(), any(Boolean.class));
    }

    @Test
    void organizationCanSetAvailabilityTrue() {
        User organizationUser = user(8L, UserRole.ORGANIZATION);
        Organization organization = Organization.builder()
                .id(18L)
                .user(organizationUser)
                .isAvailable(true)
                .availabilityPreference(true)
                .build();
        when(userService.getCurrentUser()).thenReturn(organizationUser);
        when(organizationRepository.setManualAvailability(8L, true)).thenReturn(1);
        when(organizationRepository.findByUserId(8L)).thenReturn(Optional.of(organization));
        when(assignmentRepository.countByOrganizationIdAndStatus(18L, "ASSIGNED"))
                .thenReturn(0L);

        ProviderAvailabilityResponse response =
                service.setMyAvailability(request(true));

        assertTrue(response.getAvailable());
        assertTrue(response.getAvailabilityPreference());
        assertEquals(UserRole.ORGANIZATION, response.getRole());
        verify(organizationRepository).setManualAvailability(8L, true);
    }

    @Test
    void settingFalseDuringActiveAssignmentDoesNotChangeAssignment() {
        User organizationUser = user(8L, UserRole.ORGANIZATION);
        Organization organization = Organization.builder()
                .id(18L)
                .user(organizationUser)
                .isAvailable(false)
                .availabilityPreference(false)
                .build();
        when(userService.getCurrentUser()).thenReturn(organizationUser);
        when(organizationRepository.setManualAvailability(8L, false)).thenReturn(1);
        when(organizationRepository.findByUserId(8L)).thenReturn(Optional.of(organization));
        when(assignmentRepository.countByOrganizationIdAndStatus(18L, "ASSIGNED"))
                .thenReturn(1L);

        ProviderAvailabilityResponse response =
                service.setMyAvailability(request(false));

        assertFalse(response.getAvailable());
        assertFalse(response.getAvailabilityPreference());
        assertEquals(1L, response.getActiveAssignmentCount());
        verify(assignmentRepository, never()).save(any());
        verify(assignmentRepository, never()).delete(any());
    }

    @Test
    void settingTrueWhileClaimedMakesVolunteerAvailableWithoutChangingAssignment() {
        User volunteerUser = user(7L, UserRole.VOLUNTEER);
        Volunteer volunteer = Volunteer.builder()
                .id(17L)
                .user(volunteerUser)
                .isAvailable(true)
                .availabilityPreference(true)
                .build();
        when(userService.getCurrentUser()).thenReturn(volunteerUser);
        when(volunteerRepository.setManualAvailability(7L, true)).thenReturn(1);
        when(volunteerRepository.findByUserId(7L)).thenReturn(Optional.of(volunteer));
        when(assignmentRepository.countByVolunteerIdAndStatus(17L, "ASSIGNED"))
                .thenReturn(1L);

        ProviderAvailabilityResponse response =
                service.setMyAvailability(request(true));

        assertTrue(response.getAvailable());
        assertEquals(1L, response.getActiveAssignmentCount());
        verify(assignmentRepository, never()).save(any());
        verify(assignmentRepository, never()).delete(any());
    }

    @Test
    void beneficiaryCannotManageProviderAvailability() {
        when(userService.getCurrentUser()).thenReturn(user(9L, UserRole.BENEFICIARY));

        assertThrows(UnauthorizedException.class,
                () -> service.setMyAvailability(request(true)));

        verify(volunteerRepository, never()).setManualAvailability(any(), any(Boolean.class));
        verify(organizationRepository, never()).setManualAvailability(any(), any(Boolean.class));
    }

    private ProviderAvailabilityDto request(boolean available) {
        ProviderAvailabilityDto request = new ProviderAvailabilityDto();
        request.setAvailable(available);
        return request;
    }

    private User user(Long id, UserRole role) {
        return User.builder().id(id).fullName("Provider").role(role).build();
    }
}
