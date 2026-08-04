package com.humanitarian.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactInfoResponse {
    private String name;
    private String email;
    private String phone;
    private String contactRole;

    @Builder.Default
    private boolean anonymous = false;

    private String message;
}
