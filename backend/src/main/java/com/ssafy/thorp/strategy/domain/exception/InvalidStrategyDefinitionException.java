package com.ssafy.thorp.strategy.domain.exception;

/**
 * 전략 정의가 형식을 위반했을 때.
 *
 * <p>사용자 입력이 아니라 정의 파일의 문제이므로 {@code BusinessException}이 아니다. 카탈로그 로딩이 이 예외를 잡아 그 정의만 제외하고 나머지는 정상
 * 제공한다(FR-017).
 */
public class InvalidStrategyDefinitionException extends RuntimeException {

    private final String source;

    public InvalidStrategyDefinitionException(String source, String message, Throwable cause) {
        super("%s: %s".formatted(source, message), cause);
        this.source = source;
    }

    /** 위반한 정의의 출처(파일 경로 또는 전략 식별자). */
    public String getSource() {
        return source;
    }
}
