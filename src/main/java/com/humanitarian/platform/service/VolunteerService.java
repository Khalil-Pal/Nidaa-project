package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.VolunteerOccupationDto;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VolunteerService {

    private final VolunteerRepository volunteerRepository;
    private final UserService userService;

    public VolunteerService(VolunteerRepository volunteerRepository, UserService userService) {
        this.volunteerRepository = volunteerRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public VolunteerOccupationDto getMyOccupation() {
        return toOccupationDto(getCurrentVolunteer());
    }

    @Transactional
    public VolunteerOccupationDto updateMyOccupation(VolunteerOccupationDto request) {
        Volunteer volunteer = getCurrentVolunteer();
        volunteer.setOccupation(request.getOccupation().trim());
        return toOccupationDto(volunteerRepository.save(volunteer));
    }

    private Volunteer getCurrentVolunteer() {
        User currentUser = userService.getCurrentUser();
        if (currentUser.getRole() != UserRole.VOLUNTEER) {
            throw new UnauthorizedException("Only volunteers can manage an occupation.");
        }
        return volunteerRepository.findByUserId(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Volunteer profile not found for the current user."));
    }

    private VolunteerOccupationDto toOccupationDto(Volunteer volunteer) {
        return VolunteerOccupationDto.builder()
                .occupation(volunteer.getOccupation())
                .build();
    }
}
