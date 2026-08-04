package com.humanitarian.platform.service;

import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HelpRequestServiceTest {

    @Mock private HelpRequestRepository helpRequestRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserService userService;
    @Mock private JdbcTemplate jdbc;
    @Mock private PriorityScoreService priorityScoreService;
    @Mock private GeoMatchingService geoMatchingService;
    @Mock private AssignmentRepository assignmentRepository;
    @Mock private VolunteerRepository volunteerRepository;
    @Mock private AutomaticAssignmentService automaticAssignmentService;
    @Mock private ProviderResourceService providerResourceService;

    @InjectMocks private HelpRequestService service;

    @Test
    void organizationWithoutRequestedResourceCannotAcceptRequest() {
        User organization = User.builder()
                .id(8L)
                .role(UserRole.ORGANIZATION)
                .fullName("Aid Organization")
                .build();
        HelpRequest request = HelpRequest.builder()
                .id(90L)
                .beneficiaryId(3L)
                .helpType("SHELTER")
                .status("PENDING")
                .build();
        when(userService.getCurrentUser()).thenReturn(organization);
        when(helpRequestRepository.findById(90L)).thenReturn(Optional.of(request));
        doThrow(new BusinessException("No matching resource"))
                .when(providerResourceService).requireUsableResource(8L, "SHELTER");

        assertThrows(BusinessException.class, () -> service.assignToMe(90L));

        verify(providerResourceService).requireUsableResource(8L, "SHELTER");
        verify(helpRequestRepository, never()).assignOrganization(
                anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void rankedSuggestionSkipsCloserVolunteerWithoutRequestedResource() {
        HelpRequest request = HelpRequest.builder()
                .id(91L)
                .helpType("MEDICAL")
                .priorityScore(80)
                .latitude(55.75)
                .longitude(37.62)
                .status("PENDING")
                .build();
        Volunteer closerWrongResource = Volunteer.builder()
                .id(31L)
                .user(User.builder().id(301L).fullName("Closer Provider").build())
                .isAvailable(true)
                .latitude(55.751)
                .longitude(37.621)
                .build();
        Volunteer fartherRightResource = Volunteer.builder()
                .id(32L)
                .user(User.builder().id(302L).fullName("Matching Provider").build())
                .isAvailable(true)
                .latitude(55.80)
                .longitude(37.70)
                .build();
        when(helpRequestRepository.findByStatusOrderByPriorityScoreDesc("PENDING"))
                .thenReturn(List.of(request));
        when(providerResourceService.findEligibleProviderUserIds("MEDICAL"))
                .thenReturn(Set.of(302L));
        when(geoMatchingService.findNearestVolunteer(
                request, List.of(fartherRightResource)))
                .thenReturn(Optional.of(fartherRightResource));
        when(geoMatchingService.haversine(55.75, 37.62, 55.80, 37.70))
                .thenReturn(7.2);

        var ranked = service.getRankedWithSuggestions(
                List.of(closerWrongResource, fartherRightResource));

        assertEquals(1, ranked.size());
        assertEquals("Matching Provider", ranked.get(0).getSuggestedVolunteerName());
        assertEquals(7.2, ranked.get(0).getDistanceKm());
    }
}
