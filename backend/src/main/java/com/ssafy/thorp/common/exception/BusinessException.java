package com.ssafy.thorp.common.exception;

/**
 * 비즈니스 규칙 위반을 나타내는 최상위 예외.
 *
 * <p>도메인 예외는 이 클래스를 상속하고 {@link ErrorCode}를 지정한다. {@code GlobalExceptionHandler}가 ErrorCode의 HTTP
 * 상태와 메시지로 응답을 만든다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 외부 시스템 장애처럼 원인 예외를 남겨야 하는 경우. */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
