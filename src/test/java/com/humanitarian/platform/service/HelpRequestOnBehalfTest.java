package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.HelpRequestDto;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ON-1: a volunteer or organization filing a help request for someone else. */
@ExtendWith(MockitoExtension.class)
class HelpRequestOnBehalfTest {

    @Mock private HelpRequestRepository helpRequestRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserService userService;
    @Mock private JdbcTemplate jdbc;
    @Mock private com.humanitarian.platform.service.AdminAuditService adminAudit;
    @Mock private PriorityScoreService priorityScoreService;
    @Mock private GeoMatchingService geoMatchingService;
    @Mock private AssignmentRepository assignmentRepository;
    @Mock private VolunteerRepository volunteerRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private AutomaticAssignmentService automaticAssignmentService;
    @Mock private ProviderResourceService providerResourceService;

    @InjectMocks private HelpRequestService service;

    private static final long VOLUNTEER_USER_ID = 10L;

    private static User volunteer() {
        return User.builder()
                .id(VOLUNTEER_USER_ID)
                .email("volunteer@example.com")
                .fullName("Field Volunteer")
                .role(UserRole.VOLUNTEER)
                .build();
    }

    private static HelpRequestDto dtoFor(String name, String email, String phone) {
        HelpRequestDto dto = new HelpRequestDto();
        dto.setTitle("Food for a family");
        dto.setHelpType("FOOD");
        dto.setUrgencyLevel("HIGH");
        dto.setPeopleCount(4);
        dto.setBeneficiaryName(name);
        dto.setBeneficiaryEmail(email);
        dto.setBeneficiaryPhone(phone);
        return dto;
    }

    private void saveAssignsId(long id) {
        when(helpRequestRepository.save(any(HelpRequest.class))).thenAnswer(invocation -> {
            HelpRequest r = invocation.getArgument(0);
            r.setId(id);
            return r;
        });
    }

    @Test
    void providerFilingForNewBeneficiaryCreatesAccountAndRecordsFiler() {
        when(userService.getCurrentUser()).thenReturn(volunteer());
        when(userRepository.findByEmail("new.person@example.com")).thenReturn(Optional.empty());
        User created = User.builder().id(50L).role(UserRole.BENEFICIARY).build();
        when(userService.createUnverifiedBeneficiary("Amina Person", "new.person@example.com", "+123"))
                .thenReturn(created);
        saveAssignsId(100L);

        service.createRequest(dtoFor("Amina Person", "New.Person@example.com", "+123"));

        ArgumentCaptor<HelpRequest> captor = ArgumentCaptor.forClass(HelpRequest.class);
        verify(helpRequestRepository).save(captor.capture());
        assertEquals(50L, captor.getValue().getBeneficiaryId());
        assertEquals(VOLUNTEER_USER_ID, captor.getValue().getFiledByUserId());
    }

    @Test
    void providerFilingForExistingBeneficiaryReusesAccount() {
        when(userService.getCurrentUser()).thenReturn(volunteer());
        User existing = User.builder().id(51L).role(UserRole.BENEFICIARY).build();
        when(userRepository.findByEmail("known@example.com")).thenReturn(Optional.of(existing));
        saveAssignsId(101L);

        service.createRequest(dtoFor(null, "known@example.com", null));

        verify(userService, never()).createUnverifiedBeneficiary(anyString(), anyString(), any());
        ArgumentCaptor<HelpRequest> captor = ArgumentCaptor.forClass(HelpRequest.class);
        verify(helpRequestRepository).save(captor.capture());
        assertEquals(51L, captor.getValue().getBeneficiaryId());
        assertEquals(VOLUNTEER_USER_ID, captor.getValue().getFiledByUserId());
    }

    @Test
    void selfFiledRequestHasNoFiler() {
        User beneficiary = User.builder().id(2L).role(UserRole.BENEFICIARY).build();
        when(userService.getCurrentUser()).thenReturn(beneficiary);
        saveAssignsId(102L);

        service.createRequest(dtoFor(null, null, null));

        ArgumentCaptor<HelpRequest> captor = ArgumentCaptor.forClass(HelpRequest.class);
        verify(helpRequestRepository).save(captor.capture());
        assertEquals(2L, captor.getValue().getBeneficiaryId());
        assertNull(captor.getValue().getFiledByUserId());
    }

    @Test
    void beneficiarySupplyingBeneficiaryEmailIsRejected() {
        User beneficiary = User.builder().id(2L).role(UserRole.BENEFICIARY).build();
        when(userService.getCurrentUser()).thenReturn(beneficiary);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createRequest(dtoFor(null, "someone@example.com", null)));

        assertTrue(ex.getMessage().contains("Only volunteers and organizations"));
        verify(helpRequestRepository, never()).save(any());
        verify(userService, never()).createUnverifiedBeneficiary(anyString(), anyString(), any());
    }

    @Test
    void filingForAProviderAccountIsRejected() {
        when(userService.getCurrentUser()).thenReturn(volunteer());
        User otherVolunteer = User.builder().id(11L).role(UserRole.VOLUNTEER).build();
        when(userRepository.findByEmail("colleague@example.com")).thenReturn(Optional.of(otherVolunteer));

        assertThrows(BusinessException.class,
                () -> service.createRequest(dtoFor(null, "colleague@example.com", null)));

        verify(helpRequestRepository, never()).save(any());
    }

    @Test
    void newBeneficiaryWithoutNameIsRejected() {
        when(userService.getCurrentUser()).thenReturn(volunteer());
        when(userRepository.findByEmail("nameless@example.com")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class,
                () -> service.createRequest(dtoFor(null, "nameless@example.com", null)));

        verify(userService, never()).createUnverifiedBeneficiary(anyString(), anyString(), any());
        verify(helpRequestRepository, never()).save(any());
    }

    @Test
    void filerCannotAcceptRequestTheyFiled() {
        when(userService.getCurrentUser()).thenReturn(volunteer());
        HelpRequest filed = HelpRequest.builder()
                .id(200L)
                .beneficiaryId(50L)
                .filedByUserId(VOLUNTEER_USER_ID)
                .helpType("FOOD")
                .peopleCount(4)
                .status("PENDING")
                .build();
        when(helpRequestRepository.findById(200L)).thenReturn(Optional.of(filed));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.assignToMe(200L));

        assertTrue(ex.getMessage().contains("cannot accept a request you filed"));
        verify(providerResourceService, never()).requireUsableResource(anyLong(), anyString(), anyInt());
        verify(volunteerRepository, never()).claimIfAvailable(anyLong());
        verify(helpRequestRepository, never()).assignVolunteer(anyLong(), anyLong(), anyString(), anyString());
    }
}
