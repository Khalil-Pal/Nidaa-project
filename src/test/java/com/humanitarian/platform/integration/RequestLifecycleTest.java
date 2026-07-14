package com.humanitarian.platform.integration;

import com.humanitarian.platform.dto.HelpRequestDto;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.service.GeoMatchingService;
import com.humanitarian.platform.service.HelpRequestService;
import com.humanitarian.platform.service.PriorityScoreService;
import com.humanitarian.platform.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestLifecycleTest {

    @Mock private HelpRequestRepository helpRequestRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserService userService;
    @Mock private JdbcTemplate jdbc;
    @Mock private AssignmentRepository assignmentRepository;
    @Mock private GeoMatchingService geoMatchingService;
    @Spy private PriorityScoreService priorityScoreService = new PriorityScoreService();

    @InjectMocks private HelpRequestService helpRequestService;

    @Test
    void fullLifecyclePendingAssignedCompleted() {
        User beneficiary = User.builder()
                .id(1L)
                .fullName("Beneficiary One")
                .email("beneficiary@example.test")
                .role(UserRole.BENEFICIARY)
                .build();
        User volunteer = User.builder()
                .id(2L)
                .fullName("Volunteer One")
                .email("volunteer@example.test")
                .role(UserRole.VOLUNTEER)
                .build();

        when(userService.getCurrentUser()).thenReturn(beneficiary, volunteer, volunteer);
        when(helpRequestRepository.save(any(HelpRequest.class))).thenAnswer(invocation -> {
            HelpRequest request = invocation.getArgument(0);
            request.setId(10L);
            return request;
        });
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(volunteer.getId()))).thenReturn(20L);
        when(helpRequestRepository.assignVolunteer(10L, 20L, "ASSIGNED", "PENDING")).thenReturn(1);
        when(userRepository.findById(beneficiary.getId())).thenReturn(Optional.of(beneficiary));

        HelpRequest assigned = HelpRequest.builder()
                .id(10L)
                .beneficiaryId(beneficiary.getId())
                .title("Need food")
                .status("ASSIGNED")
                .build();
        HelpRequest completed = HelpRequest.builder()
                .id(10L)
                .beneficiaryId(beneficiary.getId())
                .title("Need food")
                .status("COMPLETED")
                .build();
        when(helpRequestRepository.findById(10L))
                .thenReturn(Optional.of(assigned))
                .thenReturn(Optional.of(assigned))
                .thenReturn(Optional.of(completed));

        Assignment assignment = Assignment.builder()
                .requestId(10L)
                .volunteerId(20L)
                .status("ASSIGNED")
                .build();
        when(assignmentRepository.findFirstByRequestIdAndStatusOrderByAssignedAtDesc(10L, "ASSIGNED"))
                .thenReturn(Optional.of(assignment));

        HelpRequestDto dto = new HelpRequestDto();
        dto.setTitle("Need food");
        dto.setHelpType("FOOD");
        dto.setUrgencyLevel("HIGH");
        dto.setPeopleCount(3);

        HelpRequest created = helpRequestService.createRequest(dto);
        assertEquals("PENDING", created.getStatus());
        assertTrue(created.getPriorityScore() > 0);

        helpRequestService.assignToMe(10L);

        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertEquals("ASSIGNED", captor.getValue().getStatus());
        assertEquals(20L, captor.getValue().getVolunteerId());

        HelpRequest updated = helpRequestService.updateStatus(10L, "COMPLETED");

        assertEquals("COMPLETED", updated.getStatus());
        assertEquals("COMPLETED", assignment.getStatus());
        verify(helpRequestRepository).updateStatusCompleted(eq(10L), eq("COMPLETED"), any());
    }
}
