package com.ssafy.thorp.account.infrastructure.client;

import com.ssafy.thorp.account.application.port.VerificationMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class VerificationMailSender
    implements VerificationMailSender {

    private final JavaMailSender javaMailSender;

    public VerificationMailSender(
        JavaMailSender javaMailSender
    ) {
        this.javaMailSender = javaMailSender;
    }

    @Override
    public void sendVerificationCode(
        String email,
        String verificationCode
    ) {
        SimpleMailMessage message = new SimpleMailMessage();

        message.setTo(email);
        message.setSubject("THORP 이메일 인증번호");
        message.setText(
            "이메일 인증번호는 "
                + verificationCode
                + " 입니다.\n"
                + "인증번호는 3분 동안 유효합니다."
        );

        javaMailSender.send(message);
    }
}
