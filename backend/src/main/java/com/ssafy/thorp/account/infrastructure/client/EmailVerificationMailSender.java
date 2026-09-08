package com.ssafy.thorp.account.infrastructure.client;

import com.ssafy.thorp.account.application.port.VerificationMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class EmailVerificationMailSender implements VerificationMailSender {

    private final JavaMailSender javaMailSender;
    private final String from;

    public EmailVerificationMailSender(
        JavaMailSender javaMailSender,
        @Value("${spring.mail.username}") String from
    ) {
        this.javaMailSender = javaMailSender;
        this.from = from;
    }

    @Override
    public void sendVerificationCode(String email, String verificationCode) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
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
