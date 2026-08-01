package com.humanitarian.platform.service.matching;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Volunteer;

import java.util.List;
import java.util.Optional;

public interface MatchingStrategy {
    List<HelpRequest> rank(List<HelpRequest> requests);

    default List<HelpRequest> rank(List<HelpRequest> requests, List<Volunteer> volunteers) {
        return rank(requests);
    }

    default Optional<Volunteer> selectVolunteer(HelpRequest request,
                                                List<Volunteer> availableVolunteers) {
        return availableVolunteers.stream().findFirst();
    }

    String getName();
}
