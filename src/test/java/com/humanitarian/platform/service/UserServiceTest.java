package com.humanitarian.platform.service;

import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.ProfileRepository;
import com.humanitarian.platform.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ProfileRepository profileRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private com.humanitarian.platform.repository.RefreshTokenRepository refreshTokenRepository;

    @InjectMocks private UserService service;

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // -- D-2: soft delete ------------------------------------------------------

    @Test
    void deleteAccountAnonymisesInPlaceAndEndsSessions() {
        User user = User.builder().id(9L).email("person@example.com").role(UserRole.BENEFICIARY).build();
        com.humanitarian.platform.model.Profile profile = com.humanitarian.platform.model.Profile.builder()
                .user(user).address("12 Home Street").latitude(55.7).longitude(37.6).bio("about me").build();
        when(userRepository.findById(9L)).thenReturn(java.util.Optional.of(user));
        when(userRepository.softDelete(eq(9L), any(java.time.LocalDateTime.class))).thenReturn(1);
        when(profileRepository.findByUserId(9L)).thenReturn(java.util.Optional.of(profile));

        service.deleteAccount(9L);

        verify(userRepository).softDelete(eq(9L), any(java.time.LocalDateTime.class));
        verify(userRepository, never()).delete(any(User.class));
        verify(userRepository, never()).deleteById(any());
        assertNull(profile.getAddress());
        assertNull(profile.getLatitude());
        assertNull(profile.getLongitude());
        assertNull(profile.getBio());
        verify(profileRepository).save(profile);
        verify(refreshTokenRepository).deleteByEmail("person@example.com");
    }

    @Test
    void deleteAccountRefusesAnAlreadyDeletedAccount() {
        User gone = User.builder().id(9L).deletedAt(java.time.LocalDateTime.now().minusDays(1)).build();
        when(userRepository.findById(9L)).thenReturn(java.util.Optional.of(gone));

        assertThrows(BusinessException.class, () -> service.deleteAccount(9L));

        verify(userRepository, never()).softDelete(any(), any());
    }

    @Test
    void selfDeletionRequiresTheCurrentPassword() {
        User me = User.builder().id(9L).email("person@example.com").passwordHash("hash").build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "person@example.com", null, java.util.List.of()));
        when(userRepository.findByEmail("person@example.com")).thenReturn(java.util.Optional.of(me));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.deleteOwnAccount("wrong"));
        assertThrows(BusinessException.class, () -> service.deleteOwnAccount(""));

        verify(userRepository, never()).softDelete(any(), any());
    }

    @Test
    void onBehalfBeneficiaryIsActiveButUnverifiedWithAnUnknownPassword() {
        when(userRepository.existsByEmail("new.person@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User created = service.createUnverifiedBeneficiary(" Amina Person ", "New.Person@Example.com", " ");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("new.person@example.com", saved.getEmail());
        assertEquals("Amina Person", saved.getFullName());
        assertEquals(UserRole.BENEFICIARY, saved.getRole());
        assertTrue(saved.getIsActive(), "password reset requires an active account");
        assertFalse(saved.getIsVerified(), "the person has not proven control of the email");
        assertFalse(saved.getIsLocked());
        assertEquals("$2a$10$hashed", saved.getPasswordHash());
        assertNull(saved.getPhone());
        assertEquals(created, saved);
    }

    @Test
    void onBehalfBeneficiaryIsNotCreatedTwice() {
        when(userRepository.existsByEmail("known@example.com")).thenReturn(true);

        assertThrows(BusinessException.class,
                () -> service.createUnverifiedBeneficiary("Someone", "known@example.com", null));

        verify(userRepository, never()).save(any());
    }
}
