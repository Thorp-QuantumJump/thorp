package com.ssafy.thorp.common.exception;

import java.util.Map;

/**
 * 비즈니스 규칙 위반을 나타내는 최상위 예외.
 *
 * <p>도메인 예외는 이 클래스를 상속하고 {@link ErrorCode}를 지정한다. {@code GlobalExceptionHandler}가 ErrorCode의 HTTP
 * 상태와 메시지로 응답을 만든다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * 사용자가 다음에 무엇을 해야 하는지 판단하려면 메시지만으로 부족한 경우. 응답 {@code error.details} 로 그대로 나간다.
     *
     * <p>메시지에 숫자를 섞어 쓰지 않는 이유: 화면이 그 숫자로 재시도 시점을 계산하는데, 문장을 다듬다가 형식이 바뀌면 조용히 깨진다.
     */
    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null ? null : Map.copyOf(details);
    }

    /** 외부 시스템 장애처럼 원인 예외를 남겨야 하는 경우. */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = null;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** null = 추가 정보 없음. 응답에서 키가 생략된다. */
    public Map<String, Object> getDetails() {
        return details;
    }
}
