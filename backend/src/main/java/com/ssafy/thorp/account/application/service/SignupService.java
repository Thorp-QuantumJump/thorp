package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.presentation.request.SignupRequest;
import com.ssafy.thorp.account.presentation.response.SignupResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class SignupService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public SignupService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public SignupResponse signup(SignupRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
        }

        if (userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new IllegalArgumentException("이미 가입된 전화번호입니다.");
        }

        String passwordHash =
            passwordEncoder.encode(request.getPassword());

        User user = User.register(
            request.getEmail(),
            passwordHash,
            request.getNickname(),
            request.getPhoneNumber()
        );

        User savedUser = userRepository.save(user);

        return new SignupResponse(
            savedUser.getId(),
            savedUser.getEmail(),
            savedUser.getNickname(),
            savedUser.getStatus().name()
        );
    }
}
