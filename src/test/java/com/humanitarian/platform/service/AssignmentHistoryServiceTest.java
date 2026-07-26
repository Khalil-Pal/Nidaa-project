package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.AssignmentHistoryDTO;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentHistoryServiceTest {

    @Mock private AssignmentRepository assignmentRepository;
    @Mock private HelpRequestRepository helpRequestRepository;
    @Mock private PsychologicalRequestRepository psychologicalRequestRepository;
    @Mock private VolunteerRepository volunteerRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private PsychologistRepository psychologistRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserService userService;

    @InjectMocks private AssignmentHistoryService service;

    @Test
    void beneficiaryCanSeeAutomatedAssignmentTiming() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 20, 10, 0);
        User beneficiary = User.builder()
                .id(1L)
                .role(UserRole.BENEFICIARY)
                .fullName("Beneficiary")
                .build();
        User volunteerUser = User.builder().id(2L).fullName("Volunteer One").build();
        HelpRequest request = HelpRequest.builder()
                .id(10L)
                .beneficiaryId(1L)
                .title("Urgent food")
                .createdAt(createdAt)
                .build();
        Assignment assignment = Assignment.builder()
                .id(100L)
                .requestId(10L)
                .requestType("HELP_REQUEST")
                .volunteerId(20L)
                .assignmentSource("AUTO_GEO")
                .status("ASSIGNED")
                .assignedAt(createdAt.plusMinutes(30))
                .build();
        Volunteer volunteer = Volunteer.builder().id(20L).user(volunteerUser).build();

        when(userService.getCurrentUser()).thenReturn(beneficiary);
        when(helpRequestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(assignmentRepository.findByRequestIdOrderByAssignedAtAsc(10L))
                .thenReturn(List.of(assignment));
        when(volunteerRepository.findById(20L)).thenReturn(Optional.of(volunteer));

        List<AssignmentHistoryDTO> history = service.getHelpRequestHistory(10L);

        assertEquals(1, history.size());
        assertEquals(30L, history.get(0).getWaitingTimeMinutes());
        assertEquals("Volunteer One", history.get(0).getAssigneeName());
        assertTrue(history.get(0).isAutomated());
    }

    @Test
    void unrelatedBeneficiaryCannotSeeHistory() {
        User otherBeneficiary = User.builder()
                .id(2L)
                .role(UserRole.BENEFICIARY)
                .build();
        HelpRequest request = HelpRequest.builder()
                .id(10L)
                .beneficiaryId(1L)
                .build();

        when(userService.getCurrentUser()).thenReturn(otherBeneficiary);
        when(helpRequestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(assignmentRepository.findByRequestIdOrderByAssignedAtAsc(10L))
                .thenReturn(List.of());

        assertThrows(UnauthorizedException.class,
                () -> service.getHelpRequestHistory(10L));
    }
}
