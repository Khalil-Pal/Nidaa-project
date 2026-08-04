package com.humanitarian.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderCapacityAssessment {
    private Long userId;
    private String capacityMode;
    private Integer capacityAmount;
    private Boolean capacitySufficient;
}
