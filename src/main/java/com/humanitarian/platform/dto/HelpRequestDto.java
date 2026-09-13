package com.humanitarian.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    // On-behalf-of filing (ON-1). Only a VOLUNTEER or ORGANIZATION may set these;
    // when present the request is created for the person they identify and the
    // caller is recorded as the filer. Absent means the caller files for themselves.
    @Size(max = 100, message = "Beneficiary name is too long")
    private String beneficiaryName;

    @Email(message = "Beneficiary email must be a valid address")
    private String beneficiaryEmail;

    @Size(max = 20, message = "Beneficiary phone number is too long")
    private String beneficiaryPhone;

    /** True when any on-behalf-of field was supplied. Not a bean getter on purpose. */
    public boolean targetsAnotherPerson() {
        return hasText(beneficiaryName) || hasText(beneficiaryEmail) || hasText(beneficiaryPhone);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}