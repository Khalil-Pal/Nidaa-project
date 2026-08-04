package com.humanitarian.platform.dto;

import com.humanitarian.platform.model.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderAvailabilityResponse {

    private Long userId;
    private Long providerId;
    private UserRole role;
    private Boolean available;
    private Boolean availabilityPreference;
    private long activeAssignmentCount;
}
