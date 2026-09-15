package com.ssafy.thorp.account.application;

import java.util.Locale;

/** 계정 식별에 사용하는 이메일을 하나의 비교·저장 형태로 바꾼다. */
public final class EmailNormalizer {

    private EmailNormalizer() {}

    /** 앞뒤 공백을 제거하고 로케일에 영향받지 않는 소문자로 변환한다. */
    public static String normalize(String email) {
        if (email == null) {
            throw new IllegalArgumentException("email must not be null");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
