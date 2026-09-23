package com.ssafy.thorp.account.presentation.response;

public class SignupResponse {

    private final Long userId;
    private final String email;
    private final String nickname;
    private final String status;

    public SignupResponse(Long userId, String email, String nickname, String status) {
        this.userId = userId;
        this.email = email;
        this.nickname = nickname;
        this.status = status;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getNickname() {
        return nickname;
    }

    public String getStatus() {
        return status;
    }
}
