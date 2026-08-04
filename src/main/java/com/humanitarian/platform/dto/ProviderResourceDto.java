package com.humanitarian.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProviderResourceDto {

    @NotBlank(message = "Help type is required")
    private String helpType;

    @NotBlank(message = "Capacity mode is required")
    private String capacityMode;

    private Integer capacityAmount;
    private String capacityLabel;
}
