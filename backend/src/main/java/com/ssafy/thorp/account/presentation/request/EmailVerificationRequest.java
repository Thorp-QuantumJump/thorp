package com.ssafy.thorp.account.presentation.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class EmailVerificationRequest {

    @NotBlank @Email private String email;

    public String getEmail() {
        return email;
    }
}
