package com.ssafy.thorp.account.presentation.controller;

import com.ssafy.thorp.account.application.service.EmailVerificationService;
import com.ssafy.thorp.account.presentation.request.EmailVerificationConfirmRequest;
import com.ssafy.thorp.account.presentation.request.EmailVerificationRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts/email-verifications")
public class EmailVerificationController {
    private final EmailVerificationService emailVerificationService;

    public EmailVerificationController(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    @PostMapping
    public ResponseEntity<Void> sendVerificationCode(
            @Valid @RequestBody EmailVerificationRequest request) {
        emailVerificationService.createVerificationCode(request.getEmail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirmVerificationCode(
            @Valid @RequestBody EmailVerificationConfirmRequest request) {

        emailVerificationService.verifyCode(request.getEmail(), request.getVerificationCode());

        return ResponseEntity.noContent().build();
    }
}
