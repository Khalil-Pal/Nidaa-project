package com.humanitarian.platform.dto;

import com.humanitarian.platform.model.HelpRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankedRequestDTO {
    private HelpRequest request;
    private int priorityScore;
    private String suggestedVolunteerName;
    private Double distanceKm;
}
