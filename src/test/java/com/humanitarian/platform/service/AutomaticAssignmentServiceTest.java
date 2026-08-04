package com.humanitarian.platform.service;

import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutomaticAssignmentServiceTest {

    @Mock private HelpRequestRepository helpRequestRepository;
    @Mock private PsychologicalRequestRepository psychologicalRequestRepository;
    @Mock private VolunteerRepository volunteerRepository;
    @Mock private PsychologistRepository psychologistRepository;
    @Mock private AssignmentRepository assignmentRepository;
    @Spy private GeoMatchingService geoMatchingService = new GeoMatchingService();
    @Mock private ProviderResourceService providerResourceService;

    @InjectMocks private AutomaticAssignmentService service;

    @Test
    void nearestAvailableVolunteerIsClaimedAndRecorded() {
        HelpRequest request = HelpRequest.builder()
                .id(10L)
                .helpType("FOOD")
                .latitude(55.75)
                .longitude(37.62)
                .status("PENDING")
                .build();
        Volunteer volunteer = Volunteer.builder()
                .id(20L)
                .user(User.builder().id(200L).build())
                .isAvailable(true)
                .latitude(55.76)
                .longitude(37.63)
                .build();

        when(providerResourceService.findEligibleProviderUserIds("FOOD"))
                .thenReturn(Set.of(200L));
        when(volunteerRepository.findByIsAvailableTrue()).thenReturn(List.of(volunteer));
        when(volunteerRepository.claimIfAvailable(20L)).thenReturn(1);
        when(helpRequestRepository.assignVolunteer(10L, 20L, "ASSIGNED", "PENDING"))
                .thenReturn(1);

        assertTrue(service.assignNearestVolunteer(request));

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
                .user(User.builder().id(201L).build())
                .isAvailable(true)
                .latitude(55.751)
                .longitude(37.621)
                .build();
        Volunteer fartherRightResource = Volunteer.builder()
                .id(22L)
                .user(User.builder().id(202L).build())
                .isAvailable(true)
                .latitude(55.80)
                .longitude(37.70)
                .build();

        when(providerResourceService.findEligibleProviderUserIds("WATER"))
                .thenReturn(Set.of(202L));
        when(volunteerRepository.findByIsAvailableTrue())
                .thenReturn(List.of(closerWrongResource, fartherRightResource));
        when(volunteerRepository.claimIfAvailable(22L)).thenReturn(1);
        when(helpRequestRepository.assignVolunteer(11L, 22L, "ASSIGNED", "PENDING"))
                .thenReturn(1);

        assertTrue(service.assignNearestVolunteer(request));

        verify(volunteerRepository, never()).claimIfAvailable(21L);
        verify(volunteerRepository).claimIfAvailable(22L);
        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertEquals(22L, captor.getValue().getVolunteerId());
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
}
