package com.ssafy.thorp.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 비즈니스 예외의 종류와 HTTP 상태 매핑.
 *
 * <p>응답의 {@code error.code}에는 enum 이름이 그대로 실린다. 도메인별 세부 코드가 필요해지면 여기에 추가한다.
 */
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    RESULT_NOT_READY(HttpStatus.CONFLICT, "결과가 아직 준비되지 않았습니다. 잠시 후 다시 조회해 주세요."),
    BACKTEST_FAILED(HttpStatus.CONFLICT, "백테스트가 실패해 결과가 없습니다."),
    BROKER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "작업 큐에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // spec 004 백테스트 실행. 계약은 specs/004-backtest-execution/contracts/rest-api.md "오류 코드 추가분".
    RULES_NOT_REGISTERED(
            HttpStatus.UNPROCESSABLE_ENTITY, "아직 계산 규칙이 정해지지 않은 전략입니다. 다른 전략을 선택해 주세요."),
    INSUFFICIENT_HISTORY(HttpStatus.UNPROCESSABLE_ENTITY, "선택한 조건에 필요한 과거 자료가 부족합니다."),
    EXECUTION_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "지금은 실행이 밀려 있습니다. 잠시 후 다시 시도해 주세요."),
    NOT_CANCELLABLE(HttpStatus.CONFLICT, "이미 끝난 분석은 취소할 수 없습니다."),
    NOT_RETRYABLE(HttpStatus.CONFLICT, "실패하거나 취소한 분석만 다시 시도할 수 있습니다."),
    NO_PARTIAL_RESULT(HttpStatus.CONFLICT, "취소 시점까지 완료한 구간이 없어 보여드릴 결과가 없습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** 클라이언트에 노출되는 기본 메시지. */
    public String getMessage() {
        return message;
    }
}
