package com.ssafy.thorp.assistant.application.result;

/**
 * 질문이 가리키는 화면. 종류와 그 종류의 식별자 하나만 갖는다.
 *
 * <p>{@code RESULT}의 식별자는 공개 토큰이다. 토큰은 서버 안에서만 쓰이고 모델에는 가지 않는다(FR-008).
 */
public record PageContext(PageKind kind, String publicToken) {}
