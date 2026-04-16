package com.humanitarian.platform.dto;

import lombok.Data;

@Data
public class UserProfileDto {
    private String fullName;
    private String phone;
    private String bio;
    private String address;
    private String preferredLanguage;
    private Double latitude;
    private Double longitude;
}