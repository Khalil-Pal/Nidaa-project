package com.humanitarian.platform.dto;

import com.humanitarian.platform.model.PsychologicalRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankedPsychologicalRequestDTO {
    private PsychologicalRequest request;
    private int priorityScore;
}
