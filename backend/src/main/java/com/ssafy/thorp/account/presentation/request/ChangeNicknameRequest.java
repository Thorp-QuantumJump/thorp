package com.ssafy.thorp.account.presentation.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 회원정보 수정의 닉네임 변경 요청. 가입과 같은 길이 규칙(2~20자)을 쓴다. */
public class ChangeNicknameRequest {

    @NotBlank
    @Size(min = 2, max = 20)
    private String nickname;

    public String getNickname() {
        return nickname;
    }

    /** 검증 전에 앞뒤 공백을 뗀다. 뒤에 떼면 공백으로 길이를 채운 한 글자 닉네임이 검증을 통과한다. */
    @JsonSetter
    public void setNickname(String nickname) {
        this.nickname = nickname == null ? null : nickname.strip();
    }
}
