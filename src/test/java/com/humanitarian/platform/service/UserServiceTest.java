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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ProfileRepository profileRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private UserService service;

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
