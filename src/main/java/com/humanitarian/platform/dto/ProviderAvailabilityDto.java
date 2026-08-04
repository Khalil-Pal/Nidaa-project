package com.humanitarian.platform.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProviderAvailabilityDto {

    @NotNull(message = "Availability is required")
    private Boolean available;
}
