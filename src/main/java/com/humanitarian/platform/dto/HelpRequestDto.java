package com.humanitarian.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class HelpRequestDto {

    @NotBlank(message = "Title is required")
    private String title;

    // Description is optional - some requests are simple
    private String description;

    @NotNull(message = "Help type is required")
    private String helpType;

    @NotNull(message = "Urgency level is required")
    private String urgencyLevel;

    // Accept both "peopleCount" and "numberOfPeople" from frontend
    private Integer peopleCount;
    private String numberOfPeople;   // frontend sends this as text e.g. "3 adults"

    private Boolean hasChildren;
    private Boolean hasElderly;
    private Boolean hasDisabled;
    private String address;
    private Double latitude;
    private Double longitude;
}