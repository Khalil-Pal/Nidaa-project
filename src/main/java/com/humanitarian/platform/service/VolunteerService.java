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

    @Transactional
    public VolunteerOccupationDto updateMyOccupation(VolunteerOccupationDto request) {
        User currentUser = userService.getCurrentUser();
        if (currentUser.getRole() != UserRole.VOLUNTEER) {
            throw new UnauthorizedException("Only volunteers can update an occupation.");
        }

        Volunteer volunteer = volunteerRepository.findByUserId(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Volunteer profile not found for the current user."));
        volunteer.setOccupation(request.getOccupation().trim());
        Volunteer saved = volunteerRepository.save(volunteer);

        return VolunteerOccupationDto.builder()
                .occupation(saved.getOccupation())
                .build();
    }
}
