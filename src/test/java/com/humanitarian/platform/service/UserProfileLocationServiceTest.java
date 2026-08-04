package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.UserProfileDto;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.model.Profile;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.ProfileRepository;
import com.humanitarian.platform.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileLocationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ProfileRepository profileRepository;

    @InjectMocks private UserService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void organizationLocationSaveAndLoadRoundTripUsesProfile() {
        User organization = User.builder()
                .id(8L)
                .email("org@example.com")
                .fullName("Relief Organization")
                .role(UserRole.ORGANIZATION)
                .build();
        Profile profile = Profile.builder().id(18L).user(organization).build();
        UserProfileDto update = new UserProfileDto();
        update.setAddress("Central warehouse");
        update.setLatitude(31.9539);
        update.setLongitude(35.9106);
        when(userRepository.findById(8L)).thenReturn(Optional.of(organization));
        when(userRepository.findByEmail("org@example.com"))
                .thenReturn(Optional.of(organization));
        when(profileRepository.findByUserId(8L)).thenReturn(Optional.of(profile));
        when(profileRepository.save(any(Profile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("org@example.com", null));

        service.updateProfile(8L, update);
        UserProfileDto loaded = service.getCurrentProfile();

        assertEquals("Central warehouse", loaded.getAddress());
        assertEquals(31.9539, loaded.getLatitude());
        assertEquals(35.9106, loaded.getLongitude());
        verify(profileRepository).save(profile);
    }

    @Test
    void rejectsHalfOfCoordinatePairBeforeSaving() {
        UserProfileDto update = new UserProfileDto();
        update.setLatitude(31.9539);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.updateProfile(8L, update));

        assertEquals("Latitude and longitude must be provided together.",
                exception.getMessage());
        verify(userRepository, never()).save(any());
        verify(profileRepository, never()).save(any());
    }
}
