package com.humanitarian.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Body of DELETE /api/users/me: the caller re-enters their password (D-2). */
@Data
public class DeleteAccountRequest {

    @NotBlank(message = "Enter your current password to delete the account.")
    private String password;
}
