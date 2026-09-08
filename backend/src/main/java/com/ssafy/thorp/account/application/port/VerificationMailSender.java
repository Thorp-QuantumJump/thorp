package com.ssafy.thorp.account.application.port;

public interface VerificationMailSender {

    void sendVerificationCode(String email, String verificationCode);
}
