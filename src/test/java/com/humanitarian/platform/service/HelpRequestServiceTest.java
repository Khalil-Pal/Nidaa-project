package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.ProviderCapacityAssessment;
import com.humanitarian.platform.dto.ProviderCapacityReservation;
import com.humanitarian.platform.dto.RankedRequestDTO;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Profile;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @Mock private OrganizationRepository organizationRepository;
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
                .peopleCount(6)
                .status("PENDING")
                .build();
        when(userService.getCurrentUser()).thenReturn(organization);
        when(helpRequestRepository.findById(90L)).thenReturn(Optional.of(request));
        doThrow(new BusinessException("No matching resource"))
                .when(providerResourceService).requireUsableResource(8L, "SHELTER", 6);

        assertThrows(BusinessException.class, () -> service.assignToMe(90L));

        verify(providerResourceService).requireUsableResource(8L, "SHELTER", 6);
        verify(organizationRepository, never()).claimIfAvailable(anyLong());
        verify(helpRequestRepository, never()).assignOrganization(
                anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void rankedSuggestionSkipsCloserVolunteerWithoutRequestedResource() {
        HelpRequest request = HelpRequest.builder()
                .id(91L)
                .helpType("MEDICAL")
                .priorityScore(80)
                .peopleCount(12)
                .latitude(55.75)
                .longitude(37.62)
                .status("PENDING")
                .build();
        Volunteer closerWrongResource = Volunteer.builder()
                .id(31L)
                .user(userAt(301L, "Closer Provider", 55.751, 37.621))
                .isAvailable(true)
                .build();
        Volunteer fartherRightResource = Volunteer.builder()
                .id(32L)
                .user(userAt(302L, "Matching Provider", 55.80, 37.70))
                .isAvailable(true)
                .build();
        when(helpRequestRepository.findByStatusOrderByPriorityScoreDesc("PENDING"))
                .thenReturn(List.of(request));
        when(providerResourceService.findEligibleProviderCapacityAssessments("MEDICAL", 12))
                .thenReturn(Map.of(302L, capacity(302L, "NUMERIC", 12, true)));
        when(geoMatchingService.findNearestProvider(
                request, List.of(fartherRightResource), List.of()))
                .thenReturn(Optional.of(new GeoMatchingService.ProviderMatch(
                        UserRole.VOLUNTEER,
                        32L,
                        302L,
                        "Matching Provider",
                        55.80,
                        37.70,
                        7.2)));

        var ranked = service.getRankedWithSuggestions(
                List.of(closerWrongResource, fartherRightResource));

        assertEquals(1, ranked.size());
        assertEquals("Matching Provider", ranked.get(0).getSuggestedVolunteerName());
        assertEquals(7.2, ranked.get(0).getDistanceKm());
        assertEquals(Boolean.TRUE, ranked.get(0).getCapacitySufficient());
        assertEquals("NUMERIC", ranked.get(0).getCapacityMode());
        assertEquals(12, ranked.get(0).getCapacityAmount());
    }

    @Test
    void rankedResponseFlagsInsufficientNumericCapacity() {
        HelpRequest request = rankedRequest(94L, "FOOD", 20);
        Volunteer volunteer = rankedVolunteer(34L, 304L, "Partial Provider");

        RankedRequestDTO ranked = rankSingleVolunteer(
                request, volunteer, capacity(304L, "NUMERIC", 5, false));

        assertEquals("Partial Provider", ranked.getSuggestedVolunteerName());
        assertEquals(Boolean.FALSE, ranked.getCapacitySufficient());
        assertEquals("NUMERIC", ranked.getCapacityMode());
        assertEquals(5, ranked.getCapacityAmount());
    }

    @Test
    void rankedResponseLeavesQualitativeCapacityUnknown() {
        HelpRequest request = rankedRequest(95L, "WATER", 20);
        Volunteer volunteer = rankedVolunteer(35L, 305L, "Qualitative Provider");

        RankedRequestDTO ranked = rankSingleVolunteer(
                request, volunteer, capacity(305L, "QUALITATIVE", null, null));

        assertEquals("Qualitative Provider", ranked.getSuggestedVolunteerName());
        assertNull(ranked.getCapacitySufficient());
        assertEquals("QUALITATIVE", ranked.getCapacityMode());
        assertNull(ranked.getCapacityAmount());
    }

    @Test
    void volunteerClaimIsReleasedWhenManualAssignmentLosesRace() {
        User volunteer = User.builder()
                .id(9L)
                .role(UserRole.VOLUNTEER)
                .fullName("Volunteer")
                .build();
        HelpRequest request = HelpRequest.builder()
                .id(92L)
                .helpType("FOOD")
                .status("PENDING")
                .build();
        when(userService.getCurrentUser()).thenReturn(volunteer);
        when(helpRequestRepository.findById(92L)).thenReturn(Optional.of(request));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(9L))).thenReturn(41L);
        when(volunteerRepository.claimIfAvailable(41L)).thenReturn(1);
        ProviderCapacityReservation reservation = new ProviderCapacityReservation(
                9L, "FOOD", 1);
        when(providerResourceService.reserveForAssignment(9L, "FOOD", 1))
                .thenReturn(Optional.of(reservation));
        when(helpRequestRepository.assignVolunteer(92L, 41L, "ASSIGNED", "PENDING"))
                .thenReturn(0);

        assertThrows(BusinessException.class, () -> service.assignToMe(92L));

        verify(providerResourceService).restoreReservation(reservation);
        verify(volunteerRepository).release(41L);
    }

    @Test
    void organizationClaimIsReleasedWhenManualAssignmentLosesRace() {
        User organization = User.builder()
                .id(10L)
                .role(UserRole.ORGANIZATION)
                .fullName("Organization")
                .build();
        HelpRequest request = HelpRequest.builder()
                .id(93L)
                .helpType("WATER")
                .status("PENDING")
                .build();
        when(userService.getCurrentUser()).thenReturn(organization);
        when(helpRequestRepository.findById(93L)).thenReturn(Optional.of(request));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(10L))).thenReturn(42L);
        when(organizationRepository.claimIfAvailable(42L)).thenReturn(1);
        ProviderCapacityReservation reservation = new ProviderCapacityReservation(
                10L, "WATER", 1);
        when(providerResourceService.reserveForAssignment(10L, "WATER", 1))
                .thenReturn(Optional.of(reservation));
        when(helpRequestRepository.assignOrganization(93L, 42L, "ASSIGNED", "PENDING"))
                .thenReturn(0);

        assertThrows(BusinessException.class, () -> service.assignToMe(93L));

        verify(providerResourceService).restoreReservation(reservation);
        verify(organizationRepository).release(42L);
    }

    @Test
    void cancellationRestoresReservedCapacityExactlyOnce() {
        User admin = User.builder()
                .id(99L)
                .role(UserRole.ADMIN)
                .fullName("Admin")
                .build();
        HelpRequest assignedRequest = HelpRequest.builder()
                .id(96L)
                .beneficiaryId(3L)
                .helpType("FOOD")
                .status("ASSIGNED")
                .build();
        HelpRequest cancelledRequest = HelpRequest.builder()
                .id(96L)
                .beneficiaryId(3L)
                .helpType("FOOD")
                .status("CANCELLED")
                .build();
        Assignment assignment = Assignment.builder()
                .id(501L)
                .requestId(96L)
                .volunteerId(41L)
                .resourceUserId(9L)
                .resourceHelpType("FOOD")
                .reservedCapacityAmount(5)
                .status("ASSIGNED")
                .build();
        when(userService.getCurrentUser()).thenReturn(admin);
        when(helpRequestRepository.findById(96L))
                .thenReturn(Optional.of(assignedRequest), Optional.of(cancelledRequest));
        when(assignmentRepository.findFirstByRequestIdAndStatusOrderByAssignedAtDesc(
                96L, "ASSIGNED"))
                .thenReturn(Optional.of(assignment));
        when(assignmentRepository.markCapacityRestored(
                org.mockito.ArgumentMatchers.eq(501L),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(1);
        when(assignmentRepository.countByVolunteerIdAndStatus(41L, "ASSIGNED"))
                .thenReturn(0L);

        HelpRequest result = service.updateStatus(96L, "CANCELLED");

        assertEquals("CANCELLED", result.getStatus());
        verify(providerResourceService).restoreReservation(
                new ProviderCapacityReservation(9L, "FOOD", 5));
        verify(assignmentRepository).markCapacityRestored(
                org.mockito.ArgumentMatchers.eq(501L),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class));
        verify(volunteerRepository).release(41L);
        assertEquals("CANCELLED", assignment.getStatus());
    }

    private RankedRequestDTO rankSingleVolunteer(HelpRequest request,
                                                  Volunteer volunteer,
                                                  ProviderCapacityAssessment capacity) {
        when(helpRequestRepository.findByStatusOrderByPriorityScoreDesc("PENDING"))
                .thenReturn(List.of(request));
        when(providerResourceService.findEligibleProviderCapacityAssessments(
                request.getHelpType(), request.getPeopleCount()))
                .thenReturn(Map.of(volunteer.getUser().getId(), capacity));
        when(geoMatchingService.findNearestProvider(
                request, List.of(volunteer), List.of()))
                .thenReturn(Optional.of(new GeoMatchingService.ProviderMatch(
                        UserRole.VOLUNTEER,
                        volunteer.getId(),
                        volunteer.getUser().getId(),
                        volunteer.getUser().getFullName(),
                        volunteer.getUser().getProfile().getLatitude(),
                        volunteer.getUser().getProfile().getLongitude(),
                        2.5)));

        return service.getRankedWithSuggestions(List.of(volunteer)).get(0);
    }

    private HelpRequest rankedRequest(Long id, String helpType, Integer peopleCount) {
        return HelpRequest.builder()
                .id(id)
                .helpType(helpType)
                .peopleCount(peopleCount)
                .priorityScore(80)
                .latitude(55.75)
                .longitude(37.62)
                .status("PENDING")
                .build();
    }

    private Volunteer rankedVolunteer(Long id, Long userId, String name) {
        return Volunteer.builder()
                .id(id)
                .user(userAt(userId, name, 55.76, 37.63))
                .isAvailable(true)
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
