package com.ssafy.thorp.account.presentation.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.ssafy.thorp.account.application.EmailNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class EmailVerificationConfirmRequest {

    @NotBlank @Email private String email;

    @NotBlank
    @Pattern(regexp = "\\d{6}")
    private String verificationCode;

    public String getEmail() {
        return email;
    }

    @JsonSetter
    public void setEmail(String email) {
        this.email = email == null ? null : EmailNormalizer.normalize(email);
    }

    public String getVerificationCode() {
        return verificationCode;
    }
}
