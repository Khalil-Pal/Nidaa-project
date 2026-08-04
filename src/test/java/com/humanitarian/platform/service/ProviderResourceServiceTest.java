package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.ProviderResourceDto;
import com.humanitarian.platform.dto.ProviderResourceResponse;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.ProviderResource;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.ProviderResourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderResourceServiceTest {

    @Mock private ProviderResourceRepository providerResourceRepository;
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
