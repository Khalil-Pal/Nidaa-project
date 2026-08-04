package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.VolunteerOccupationDto;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VolunteerServiceTest {

    @Mock private VolunteerRepository volunteerRepository;
    @Mock private UserService userService;

    @InjectMocks private VolunteerService service;

    @Test
    void volunteerCanUpdateOccupation() {
        User user = User.builder().id(8L).role(UserRole.VOLUNTEER).build();
        Volunteer volunteer = Volunteer.builder().id(18L).user(user).build();
        VolunteerOccupationDto request = VolunteerOccupationDto.builder()
                .occupation("  Emergency nurse  ")
                .build();
        when(userService.getCurrentUser()).thenReturn(user);
        when(volunteerRepository.findByUserId(8L)).thenReturn(Optional.of(volunteer));
        when(volunteerRepository.save(volunteer)).thenReturn(volunteer);

        VolunteerOccupationDto response = service.updateMyOccupation(request);

        assertEquals("Emergency nurse", response.getOccupation());
        verify(volunteerRepository).save(volunteer);
    }

    @Test
    void nonVolunteerCannotUpdateOccupation() {
        User user = User.builder().id(8L).role(UserRole.ORGANIZATION).build();
        when(userService.getCurrentUser()).thenReturn(user);

        assertThrows(UnauthorizedException.class, () -> service.updateMyOccupation(
                VolunteerOccupationDto.builder().occupation("Coordinator").build()));

        verify(volunteerRepository, never()).save(any());
    }
}
