package com.humanitarian.platform.service.matching;

import com.humanitarian.platform.model.HelpRequest;

import java.util.List;

public interface MatchingStrategy {
    List<HelpRequest> rank(List<HelpRequest> requests);
    String getName();
}
