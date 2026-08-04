package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.ProviderCapacityAssessment;
import com.humanitarian.platform.dto.ProviderCapacityReservation;
import com.humanitarian.platform.dto.ProviderResourceDto;
import com.humanitarian.platform.dto.ProviderResourceResponse;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.ProviderResource;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.ProviderResourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderResourceServiceTest {

    @Mock private ProviderResourceRepository providerResourceRepository;
    @Mock private AssignmentRepository assignmentRepository;
    @Mock private UserService userService;

    @InjectMocks private ProviderResourceService service;

    @Test
    void savesValidNumericResource() {
        User volunteer = providerUser(UserRole.VOLUNTEER);
        ProviderResourceDto request = resourceDto("food", "numeric", 25, null);
        when(userService.getCurrentUser()).thenReturn(volunteer);
        when(providerResourceRepository.findByUserIdAndHelpType(7L, "FOOD"))
                .thenReturn(Optional.empty());
        when(providerResourceRepository.save(any(ProviderResource.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProviderResourceResponse response = service.upsertResource(request);

        assertEquals("FOOD", response.getHelpType());
        assertEquals("NUMERIC", response.getCapacityMode());
        assertEquals(25, response.getCapacityAmount());
        assertNull(response.getCapacityLabel());
    }

    @Test
    void savesValidQualitativeResource() {
        User organization = providerUser(UserRole.ORGANIZATION);
        ProviderResourceDto request = resourceDto(
                "medical", "qualitative", null, "  Limited first-aid stock  ");
        when(userService.getCurrentUser()).thenReturn(organization);
        when(providerResourceRepository.findByUserIdAndHelpType(7L, "MEDICAL"))
                .thenReturn(Optional.empty());
        when(providerResourceRepository.save(any(ProviderResource.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProviderResourceResponse response = service.upsertResource(request);

        assertEquals("QUALITATIVE", response.getCapacityMode());
        assertNull(response.getCapacityAmount());
        assertEquals("Limited first-aid stock", response.getCapacityLabel());
    }

    @Test
    void rejectsNumericResourceWithoutAmount() {
        when(userService.getCurrentUser()).thenReturn(providerUser(UserRole.VOLUNTEER));
        ProviderResourceDto request = resourceDto("WATER", "NUMERIC", null, null);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.upsertResource(request));

        assertEquals(
                "Amount must be greater than zero for NUMERIC capacity.",
                exception.getMessage());
        verify(providerResourceRepository, never()).save(any());
    }

    @Test
    void rejectsResourceFromWrongRole() {
        when(userService.getCurrentUser()).thenReturn(providerUser(UserRole.BENEFICIARY));
        ProviderResourceDto request = resourceDto("SHELTER", "NUMERIC", 2, null);

        UnauthorizedException exception = assertThrows(
                UnauthorizedException.class, () -> service.upsertResource(request));

        assertEquals(
                "Only volunteers and organizations can manage provider resources.",
                exception.getMessage());
        verify(providerResourceRepository, never()).save(any());
    }

    @Test
    void upsertReusesExistingHelpTypeRow() {
        User volunteer = providerUser(UserRole.VOLUNTEER);
        ProviderResource existing = ProviderResource.builder()
                .id(42L)
                .userId(7L)
                .helpType("CLOTHING")
                .capacityMode("QUALITATIVE")
                .capacityLabel("Small stock")
                .build();
        ProviderResourceDto request = resourceDto("clothing", "numeric", 50, null);
        when(userService.getCurrentUser()).thenReturn(volunteer);
        when(providerResourceRepository.findByUserIdAndHelpType(7L, "CLOTHING"))
                .thenReturn(Optional.of(existing));
        when(providerResourceRepository.save(same(existing))).thenReturn(existing);

        ProviderResourceResponse response = service.upsertResource(request);

        verify(providerResourceRepository).save(same(existing));
        assertEquals(42L, existing.getId());
        assertEquals("NUMERIC", response.getCapacityMode());
        assertEquals(50, response.getCapacityAmount());
        assertNull(response.getCapacityLabel());
    }

    @Test
    void eligibleProvidersExcludeZeroOrInvalidCapacity() {
        ProviderResource numeric = ProviderResource.builder()
                .userId(11L)
                .helpType("FOOD")
                .capacityMode("NUMERIC")
                .capacityAmount(5)
                .build();
        ProviderResource outOfStock = ProviderResource.builder()
                .userId(12L)
                .helpType("FOOD")
                .capacityMode("NUMERIC")
                .capacityAmount(0)
                .build();
        ProviderResource qualitative = ProviderResource.builder()
                .userId(13L)
                .helpType("FOOD")
                .capacityMode("QUALITATIVE")
                .capacityLabel("Limited stock")
                .build();
        ProviderResource blankQualitative = ProviderResource.builder()
                .userId(14L)
                .helpType("FOOD")
                .capacityMode("QUALITATIVE")
                .capacityLabel(" ")
                .build();
        when(providerResourceRepository.findByHelpType("FOOD"))
                .thenReturn(List.of(numeric, outOfStock, qualitative, blankQualitative));

        Set<Long> eligible = service.findEligibleProviderUserIds("food");

        assertEquals(Set.of(11L, 13L), eligible);
    }

    @Test
    void capacityAssessmentDistinguishesNumericCoverageAndQualitativeUnknown() {
        ProviderResource insufficient = ProviderResource.builder()
                .userId(11L)
                .helpType("FOOD")
                .capacityMode("NUMERIC")
                .capacityAmount(5)
                .build();
        ProviderResource sufficient = ProviderResource.builder()
                .userId(12L)
                .helpType("FOOD")
                .capacityMode("NUMERIC")
                .capacityAmount(20)
                .build();
        ProviderResource qualitative = ProviderResource.builder()
                .userId(13L)
                .helpType("FOOD")
                .capacityMode("QUALITATIVE")
                .capacityLabel("Large shared stock")
                .build();
        when(providerResourceRepository.findByHelpType("FOOD"))
                .thenReturn(List.of(insufficient, sufficient, qualitative));

        Map<Long, ProviderCapacityAssessment> assessments =
                service.findEligibleProviderCapacityAssessments("food", 20);

        assertFalse(assessments.get(11L).getCapacitySufficient());
        assertTrue(assessments.get(12L).getCapacitySufficient());
        assertNull(assessments.get(13L).getCapacitySufficient());
        assertEquals("QUALITATIVE", assessments.get(13L).getCapacityMode());
    }

    @Test
    void reservesOnlyAvailableNumericCapacityForPartialContribution() {
        ProviderResource resource = ProviderResource.builder()
                .userId(11L)
                .helpType("FOOD")
                .capacityMode("NUMERIC")
                .capacityAmount(5)
                .build();
        when(providerResourceRepository.findByUserIdAndHelpTypeForUpdate(11L, "FOOD"))
                .thenReturn(Optional.of(resource));

        ProviderCapacityReservation reservation = service
                .reserveForAssignment(11L, "food", 20)
                .orElseThrow();

        assertEquals(5, reservation.reservedAmount());
        assertEquals(0, resource.getCapacityAmount());
        verify(providerResourceRepository).save(same(resource));
    }

    @Test
    void reservesRequestedAmountWhenNumericCapacityIsSufficient() {
        ProviderResource resource = ProviderResource.builder()
                .userId(12L)
                .helpType("WATER")
                .capacityMode("NUMERIC")
                .capacityAmount(25)
                .build();
        when(providerResourceRepository.findByUserIdAndHelpTypeForUpdate(12L, "WATER"))
                .thenReturn(Optional.of(resource));

        ProviderCapacityReservation reservation = service
                .reserveForAssignment(12L, "WATER", 20)
                .orElseThrow();

        assertEquals(20, reservation.reservedAmount());
        assertEquals(5, resource.getCapacityAmount());
    }

    @Test
    void qualitativeResourceProducesNoNumericReservation() {
        ProviderResource resource = ProviderResource.builder()
                .userId(13L)
                .helpType("SHELTER")
                .capacityMode("QUALITATIVE")
                .capacityLabel("Two temporary rooms")
                .build();
        when(providerResourceRepository.findByUserIdAndHelpTypeForUpdate(13L, "SHELTER"))
                .thenReturn(Optional.of(resource));

        ProviderCapacityReservation reservation = service
                .reserveForAssignment(13L, "SHELTER", 4)
                .orElseThrow();

        assertNull(reservation.reservedAmount());
        verify(providerResourceRepository, never()).save(any());
    }

    @Test
    void restoresExactReservedAmount() {
        ProviderResource resource = ProviderResource.builder()
                .userId(14L)
                .helpType("MEDICAL")
                .capacityMode("NUMERIC")
                .capacityAmount(3)
                .build();
        when(providerResourceRepository.findByUserIdAndHelpTypeForUpdate(14L, "MEDICAL"))
                .thenReturn(Optional.of(resource));

        service.restoreReservation(new ProviderCapacityReservation(
                14L, "MEDICAL", 7));

        assertEquals(10, resource.getCapacityAmount());
        verify(providerResourceRepository).save(same(resource));
    }

    @Test
    void activeReservationBlocksResourceChanges() {
        User volunteer = providerUser(UserRole.VOLUNTEER);
        ProviderResource existing = ProviderResource.builder()
                .userId(7L)
                .helpType("FOOD")
                .capacityMode("NUMERIC")
                .capacityAmount(10)
                .build();
        when(userService.getCurrentUser()).thenReturn(volunteer);
        when(providerResourceRepository.findByUserIdAndHelpType(7L, "FOOD"))
                .thenReturn(Optional.of(existing));
        when(assignmentRepository.hasActiveCapacityReservation(7L, "FOOD"))
                .thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upsertResource(resourceDto(
                        "FOOD", "NUMERIC", 15, null)));

        assertEquals(
                "This resource has capacity reserved by an active assignment "
                        + "and cannot be changed yet.",
                exception.getMessage());
        verify(providerResourceRepository, never()).save(any());
    }

    @Test
    void manualEligibilityRejectsProviderWithoutUsableResource() {
        ProviderResource outOfStock = ProviderResource.builder()
                .userId(7L)
                .helpType("WATER")
                .capacityMode("NUMERIC")
                .capacityAmount(0)
                .build();
        when(providerResourceRepository.findByUserIdAndHelpType(7L, "WATER"))
                .thenReturn(Optional.of(outOfStock));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.requireUsableResource(7L, "water"));

        assertEquals(
                "Your provider profile does not list an available WATER resource for this request.",
                exception.getMessage());
    }

    private User providerUser(UserRole role) {
        return User.builder().id(7L).role(role).fullName("Provider").build();
    }

    private ProviderResourceDto resourceDto(String helpType,
                                             String capacityMode,
                                             Integer capacityAmount,
                                             String capacityLabel) {
        ProviderResourceDto dto = new ProviderResourceDto();
        dto.setHelpType(helpType);
        dto.setCapacityMode(capacityMode);
        dto.setCapacityAmount(capacityAmount);
        dto.setCapacityLabel(capacityLabel);
        return dto;
    }
}
