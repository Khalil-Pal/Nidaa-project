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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutomaticAssignmentServiceTest {

    @Mock private HelpRequestRepository helpRequestRepository;
    @Mock private PsychologicalRequestRepository psychologicalRequestRepository;
    @Mock private VolunteerRepository volunteerRepository;
    @Mock private PsychologistRepository psychologistRepository;
    @Mock private AssignmentRepository assignmentRepository;
    @Mock private GeoMatchingService geoMatchingService;

    @InjectMocks private AutomaticAssignmentService service;

    @Test
    void nearestAvailableVolunteerIsClaimedAndRecorded() {
        HelpRequest request = HelpRequest.builder()
                .id(10L)
                .latitude(55.75)
                .longitude(37.62)
                .status("PENDING")
                .build();
        Volunteer volunteer = Volunteer.builder()
                .id(20L)
                .isAvailable(true)
                .latitude(55.76)
                .longitude(37.63)
                .build();

        when(volunteerRepository.findByIsAvailableTrue()).thenReturn(List.of(volunteer));
        when(geoMatchingService.rankByDistance(request, List.of(volunteer)))
                .thenReturn(List.of(volunteer));
        when(volunteerRepository.claimIfAvailable(20L)).thenReturn(1);
        when(helpRequestRepository.assignVolunteer(10L, 20L, "ASSIGNED", "PENDING"))
                .thenReturn(1);
        when(geoMatchingService.haversine(55.75, 37.62, 55.76, 37.63))
                .thenReturn(1.3);

        assertTrue(service.assignNearestVolunteer(request));

        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertEquals("HELP_REQUEST", captor.getValue().getRequestType());
        assertEquals("AUTO_GEO", captor.getValue().getAssignmentSource());
        assertEquals(20L, captor.getValue().getVolunteerId());
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
