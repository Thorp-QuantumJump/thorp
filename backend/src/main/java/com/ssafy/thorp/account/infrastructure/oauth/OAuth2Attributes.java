package com.ssafy.thorp.account.infrastructure.oauth;

import java.util.Map;

/** 제공자 응답 JSON에서 값을 안전하게 꺼낸다. 없는 키와 다른 형태는 비어 있는 것으로 본다 — 제공자 응답 하나 때문에 콜백이 500이 되지 않게 한다. */
final class OAuth2Attributes {

    private OAuth2Attributes() {}

    static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    /** 문자열·숫자를 문자열로 바꾼다. 비었거나 공백뿐이면 {@code null}이다. */
    static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
