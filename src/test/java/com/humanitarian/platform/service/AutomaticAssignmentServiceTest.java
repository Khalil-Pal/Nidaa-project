package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.ProviderCapacityAssessment;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.Profile;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutomaticAssignmentServiceTest {

    @Mock private HelpRequestRepository helpRequestRepository;
    @Mock private PsychologicalRequestRepository psychologicalRequestRepository;
    @Mock private VolunteerRepository volunteerRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private PsychologistRepository psychologistRepository;
    @Mock private AssignmentRepository assignmentRepository;
    @Spy private GeoMatchingService geoMatchingService = new GeoMatchingService();
    @Mock private ProviderResourceService providerResourceService;

    @InjectMocks private AutomaticAssignmentService service;

    @Test
    void insufficientNumericCapacityDoesNotChangeAutomaticNearestSelection() {
        HelpRequest request = HelpRequest.builder()
                .id(10L)
                .helpType("FOOD")
                .peopleCount(20)
                .latitude(55.75)
                .longitude(37.62)
                .status("PENDING")
                .build();
        Volunteer volunteer = Volunteer.builder()
                .id(20L)
                .user(userAt(200L, "Nearby Volunteer", 55.76, 37.63))
                .isAvailable(true)
                .build();

        when(providerResourceService.findEligibleProviderCapacityAssessments("FOOD", 20))
                .thenReturn(Map.of(200L, capacity(200L, "NUMERIC", 5, false)));
        when(volunteerRepository.findByIsAvailableTrue()).thenReturn(List.of(volunteer));
        when(organizationRepository.findByIsAvailableTrue()).thenReturn(List.of());
        when(volunteerRepository.claimIfAvailable(20L)).thenReturn(1);
        when(helpRequestRepository.assignVolunteer(10L, 20L, "ASSIGNED", "PENDING"))
                .thenReturn(1);

        assertTrue(service.assignNearestProvider(request));

        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertEquals("HELP_REQUEST", captor.getValue().getRequestType());
        assertEquals("AUTO_GEO", captor.getValue().getAssignmentSource());
        assertEquals(20L, captor.getValue().getVolunteerId());
    }

    @Test
    void closerVolunteerWithoutRequestedResourceIsSkippedForFartherMatch() {
        HelpRequest request = HelpRequest.builder()
                .id(11L)
                .helpType("WATER")
                .latitude(55.75)
                .longitude(37.62)
                .status("PENDING")
                .build();
        Volunteer closerWrongResource = Volunteer.builder()
                .id(21L)
                .user(userAt(201L, "Wrong Resource", 55.751, 37.621))
                .isAvailable(true)
                .build();
        Volunteer fartherRightResource = Volunteer.builder()
                .id(22L)
                .user(userAt(202L, "Right Resource", 55.80, 37.70))
                .isAvailable(true)
                .build();

        when(providerResourceService.findEligibleProviderCapacityAssessments("WATER", 1))
                .thenReturn(capacities(202L));
        when(volunteerRepository.findByIsAvailableTrue())
                .thenReturn(List.of(closerWrongResource, fartherRightResource));
        when(organizationRepository.findByIsAvailableTrue()).thenReturn(List.of());
        when(volunteerRepository.claimIfAvailable(22L)).thenReturn(1);
        when(helpRequestRepository.assignVolunteer(11L, 22L, "ASSIGNED", "PENDING"))
                .thenReturn(1);

        assertTrue(service.assignNearestProvider(request));

        verify(volunteerRepository, never()).claimIfAvailable(21L);
        verify(volunteerRepository).claimIfAvailable(22L);
        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertEquals(22L, captor.getValue().getVolunteerId());
    }

    @Test
    void closerOrganizationIsSelectedOverFartherVolunteer() {
        HelpRequest request = request(12L, "FOOD");
        Volunteer volunteer = Volunteer.builder()
                .id(23L)
                .user(userAt(203L, "Far Volunteer", 55.90, 37.90))
                .isAvailable(true)
                .build();
        Organization organization = Organization.builder()
                .id(30L)
                .user(userAt(300L, "Close Organization", 55.751, 37.621))
                .officialName("Close Aid")
                .isAvailable(true)
                .build();

        when(providerResourceService.findEligibleProviderCapacityAssessments("FOOD", 1))
                .thenReturn(capacities(203L, 300L));
        when(volunteerRepository.findByIsAvailableTrue()).thenReturn(List.of(volunteer));
        when(organizationRepository.findByIsAvailableTrue()).thenReturn(List.of(organization));
        when(organizationRepository.claimIfAvailable(30L)).thenReturn(1);
        when(helpRequestRepository.assignOrganization(12L, 30L, "ASSIGNED", "PENDING"))
                .thenReturn(1);

        assertTrue(service.assignNearestProvider(request));

        verify(volunteerRepository, never()).claimIfAvailable(23L);
        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertEquals(30L, captor.getValue().getOrganizationId());
        assertNull(captor.getValue().getVolunteerId());
    }

    @Test
    void closerVolunteerIsSelectedOverFartherOrganization() {
        HelpRequest request = request(13L, "WATER");
        Volunteer volunteer = Volunteer.builder()
                .id(24L)
                .user(userAt(204L, "Close Volunteer", 55.751, 37.621))
                .isAvailable(true)
                .build();
        Organization organization = Organization.builder()
                .id(31L)
                .user(userAt(301L, "Far Organization", 55.90, 37.90))
                .isAvailable(true)
                .build();

        when(providerResourceService.findEligibleProviderCapacityAssessments("WATER", 1))
                .thenReturn(capacities(204L, 301L));
        when(volunteerRepository.findByIsAvailableTrue()).thenReturn(List.of(volunteer));
        when(organizationRepository.findByIsAvailableTrue()).thenReturn(List.of(organization));
        when(volunteerRepository.claimIfAvailable(24L)).thenReturn(1);
        when(helpRequestRepository.assignVolunteer(13L, 24L, "ASSIGNED", "PENDING"))
                .thenReturn(1);

        assertTrue(service.assignNearestProvider(request));

        verify(organizationRepository, never()).claimIfAvailable(31L);
        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertEquals(24L, captor.getValue().getVolunteerId());
        assertNull(captor.getValue().getOrganizationId());
    }

    @Test
    void closerOrganizationWithoutRequestedResourceIsSkipped() {
        HelpRequest request = request(14L, "SHELTER");
        Volunteer volunteer = Volunteer.builder()
                .id(25L)
                .user(userAt(205L, "Matching Volunteer", 55.80, 37.70))
                .isAvailable(true)
                .build();
        Organization organization = Organization.builder()
                .id(32L)
                .user(userAt(302L, "Wrong Resource Organization", 55.751, 37.621))
                .isAvailable(true)
                .build();

        when(providerResourceService.findEligibleProviderCapacityAssessments("SHELTER", 1))
                .thenReturn(capacities(205L));
        when(volunteerRepository.findByIsAvailableTrue()).thenReturn(List.of(volunteer));
        when(organizationRepository.findByIsAvailableTrue()).thenReturn(List.of(organization));
        when(volunteerRepository.claimIfAvailable(25L)).thenReturn(1);
        when(helpRequestRepository.assignVolunteer(14L, 25L, "ASSIGNED", "PENDING"))
                .thenReturn(1);

        assertTrue(service.assignNearestProvider(request));

        verify(organizationRepository, never()).claimIfAvailable(32L);
        verify(volunteerRepository).claimIfAvailable(25L);
    }

    @Test
    void unavailableOrganizationIsSkipped() {
        HelpRequest request = request(15L, "MEDICAL");
        Volunteer volunteer = Volunteer.builder()
                .id(26L)
                .user(userAt(206L, "Available Volunteer", 55.80, 37.70))
                .isAvailable(true)
                .build();
        Organization organization = Organization.builder()
                .id(33L)
                .user(userAt(303L, "Unavailable Organization", 55.751, 37.621))
                .isAvailable(false)
                .build();

        when(providerResourceService.findEligibleProviderCapacityAssessments("MEDICAL", 1))
                .thenReturn(capacities(206L, 303L));
        when(volunteerRepository.findByIsAvailableTrue()).thenReturn(List.of(volunteer));
        when(organizationRepository.findByIsAvailableTrue()).thenReturn(List.of(organization));
        when(volunteerRepository.claimIfAvailable(26L)).thenReturn(1);
        when(helpRequestRepository.assignVolunteer(15L, 26L, "ASSIGNED", "PENDING"))
                .thenReturn(1);

        assertTrue(service.assignNearestProvider(request));

        verify(organizationRepository, never()).claimIfAvailable(33L);
        verify(volunteerRepository).claimIfAvailable(26L);
    }

    @Test
    void organizationIsReleasedWhenRequestAssignmentLosesRace() {
        HelpRequest request = request(16L, "CLOTHING");
        Organization organization = Organization.builder()
                .id(34L)
                .user(userAt(304L, "Race Organization", 55.751, 37.621))
                .isAvailable(true)
                .build();

        when(providerResourceService.findEligibleProviderCapacityAssessments("CLOTHING", 1))
                .thenReturn(capacities(304L));
        when(volunteerRepository.findByIsAvailableTrue()).thenReturn(List.of());
        when(organizationRepository.findByIsAvailableTrue()).thenReturn(List.of(organization));
        when(organizationRepository.claimIfAvailable(34L)).thenReturn(1);
        when(helpRequestRepository.assignOrganization(16L, 34L, "ASSIGNED", "PENDING"))
                .thenReturn(0);

        assertFalse(service.assignNearestProvider(request));

        verify(organizationRepository).release(34L);
        verify(assignmentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void crisisGoesToOnDutyPsychologistWithLowestActiveLoad() {
        PsychologicalRequest request = PsychologicalRequest.builder()
                .id(30L)
                .isCrisis(true)
                .status("PENDING")
                .build();
        Psychologist busy = Psychologist.builder()
                .id(40L)
                .isVerified(true)
                .isOnDuty(true)
                .rating(5.0)
                .experienceYears(10)
                .build();
        Psychologist available = Psychologist.builder()
                .id(41L)
                .isVerified(true)
                .isOnDuty(true)
                .rating(4.5)
                .experienceYears(5)
                .build();

        when(psychologistRepository.findByIsVerifiedTrueAndIsOnDutyTrue())
                .thenReturn(List.of(busy, available));
        when(psychologicalRequestRepository.countByAssignedPsychologistIdAndStatus(40L, "ASSIGNED"))
                .thenReturn(3L);
        when(psychologicalRequestRepository.countByAssignedPsychologistIdAndStatus(41L, "ASSIGNED"))
                .thenReturn(0L);
        when(psychologicalRequestRepository.assignPsychologist(
                30L, 41L, "ASSIGNED", "PENDING")).thenReturn(1);

        assertTrue(service.routeCrisisRequest(request));

        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertEquals("PSYCHOLOGICAL_REQUEST", captor.getValue().getRequestType());
        assertEquals("AUTO_CRISIS", captor.getValue().getAssignmentSource());
        assertEquals(41L, captor.getValue().getPsychologistId());
    }

    private HelpRequest request(Long id, String helpType) {
        return HelpRequest.builder()
                .id(id)
                .helpType(helpType)
                .latitude(55.75)
                .longitude(37.62)
                .status("PENDING")
                .build();
    }

    private User userAt(Long id, String name, double latitude, double longitude) {
        User user = User.builder().id(id).fullName(name).build();
        user.setProfile(Profile.builder()
                .user(user)
                .latitude(latitude)
                .longitude(longitude)
                .build());
        return user;
    }

    private Map<Long, ProviderCapacityAssessment> capacities(Long... userIds) {
        Map<Long, ProviderCapacityAssessment> result = new HashMap<>();
        for (Long userId : userIds) {
            result.put(userId, capacity(userId, "QUALITATIVE", null, null));
        }
        return result;
    }

    private ProviderCapacityAssessment capacity(Long userId,
                                                  String mode,
                                                  Integer amount,
                                                  Boolean sufficient) {
        return ProviderCapacityAssessment.builder()
                .userId(userId)
                .capacityMode(mode)
                .capacityAmount(amount)
                .capacitySufficient(sufficient)
                .build();
    }
}
