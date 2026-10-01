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

    // spec 014 사용자 인증. 계약은 specs/014-user-auth/contracts/auth-api.yaml.
    DUPLICATE_EMAIL(HttpStatus.BAD_REQUEST, "이미 가입된 이메일입니다."),
    DUPLICATE_PHONE(HttpStatus.BAD_REQUEST, "이미 가입된 전화번호입니다."),
    EMAIL_VERIFICATION_REQUIRED(HttpStatus.BAD_REQUEST, "이메일 인증을 완료해 주세요."),
    EMAIL_VERIFICATION_NOT_FOUND(HttpStatus.BAD_REQUEST, "인증 요청을 찾을 수 없습니다. 인증번호를 다시 요청해 주세요."),
    EMAIL_VERIFICATION_INVALID(HttpStatus.BAD_REQUEST, "인증번호를 다시 확인해 주세요."),
    EMAIL_VERIFICATION_EXPIRED(HttpStatus.BAD_REQUEST, "인증번호가 만료되었습니다. 인증번호를 다시 요청해 주세요."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호를 확인해 주세요."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "요청 확인 정보가 만료되었습니다. 다시 시도해 주세요."),

    // spec 014 소셜 로그인(2026-10-01). 비밀번호·인증번호 오류는 위 코드를 그대로 쓴다 — 화면이 같은 안내로 분기한다.
    SOCIAL_PENDING_NOT_FOUND(HttpStatus.NOT_FOUND, "소셜 로그인 시간이 지났습니다. 처음부터 다시 시도해 주세요."),
    SOCIAL_PENDING_STAGE_MISMATCH(HttpStatus.CONFLICT, "지금 단계에서는 할 수 없는 요청입니다. 화면을 새로고침해 주세요."),
    // 겹친 계정이 정지·탈퇴 상태여도 그 사실을 드러내지 않는 일반 거절이다(spec Edge Cases).
    SOCIAL_SIGNUP_REJECTED(HttpStatus.BAD_REQUEST, "지금은 이 정보로 가입할 수 없습니다. 다른 방법으로 로그인해 주세요."),
    SOCIAL_LINK_REQUIRED(HttpStatus.CONFLICT, "이 번호로 가입된 계정이 있습니다. 계정 연결을 확인해 주세요."),
    SOCIAL_PROVIDER_ALREADY_LINKED(HttpStatus.CONFLICT, "이 계정은 이미 같은 제공자의 다른 계정과 연결되어 있습니다."),
    SOCIAL_ACCOUNT_ALREADY_LINKED(HttpStatus.CONFLICT, "이 소셜 계정은 다른 Thorp 계정에 연결되어 있습니다."),
    SOCIAL_PROVIDER_NOT_ENABLED(HttpStatus.NOT_FOUND, "지금은 이 방법으로 로그인할 수 없습니다."),
    // 소셜 경로의 확인 시도·발송 상한(2026-10-01 code review). 대기 상태를 버렸으므로 처음부터 다시 해야 한다.
    SOCIAL_VERIFICATION_LIMIT(
            HttpStatus.TOO_MANY_REQUESTS, "확인 시도 횟수를 넘었습니다. 소셜 로그인을 처음부터 다시 시도해 주세요."),
    // 같은 기존 계정을 향한 연결 확인이 한 시간 창의 상한을 넘었다. 그 계정의 로그인은 막지 않는다.
    SOCIAL_LINK_TEMPORARILY_LOCKED(
            HttpStatus.TOO_MANY_REQUESTS,
            "이 계정에 대한 확인 시도가 많아 잠시 막았습니다. 1시간 뒤 다시 시도하거나 다른 방법으로 로그인해 주세요."),
    EMAIL_VERIFICATION_RESEND_TOO_SOON(
            HttpStatus.TOO_MANY_REQUESTS, "인증번호를 방금 보냈습니다. 잠시 후 다시 받아 주세요."),

    // spec 004 백테스트 실행. 계약은 specs/004-backtest-execution/contracts/rest-api.md "오류 코드 추가분".
    RULES_NOT_REGISTERED(
            HttpStatus.UNPROCESSABLE_ENTITY, "아직 계산 규칙이 정해지지 않은 전략입니다. 다른 전략을 선택해 주세요."),
    INSUFFICIENT_HISTORY(HttpStatus.UNPROCESSABLE_ENTITY, "선택한 조건에 필요한 과거 자료가 부족합니다."),
    EXECUTION_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "지금은 실행이 밀려 있습니다. 잠시 후 다시 시도해 주세요."),
    NOT_CANCELLABLE(HttpStatus.CONFLICT, "이미 끝난 분석은 취소할 수 없습니다."),
    NOT_RETRYABLE(HttpStatus.CONFLICT, "실패하거나 취소한 분석만 다시 시도할 수 있습니다."),
    NO_PARTIAL_RESULT(HttpStatus.CONFLICT, "취소 시점까지 완료한 구간이 없어 보여드릴 결과가 없습니다."),
    CANCEL_PENDING(HttpStatus.CONFLICT, "같은 조건의 실행이 취소 처리 중입니다. 잠시 후 다시 요청해 주세요."),

    // spec 005 결과 이해·비교. 계약은 specs/005-result-comparison/contracts/result-api.md "오류".
    // 나머지(INVALID_INPUT·RESOURCE_NOT_FOUND·RESULT_NOT_READY·BACKTEST_FAILED·NO_PARTIAL_RESULT·
    // NOT_RETRYABLE·EXECUTION_LIMIT)는 위 004 블록의 것을 그대로 쓴다 — 공개 경로가 같은 거절을 다른 코드로 내면 화면이 두 벌로 분기한다.
    RESULT_EXPIRED(HttpStatus.GONE, "결과가 더 이상 남아 있지 않습니다. 같은 조건으로 다시 분석할 수 있습니다."),

    // spec 003 전략 선택·상세 설정. 계약은
    // specs/003-strategy-settings/contracts/strategy-registry-api.md §2.
    // 나머지 넷(UNREGISTERED_PARAMETER_VALUE·STRATEGY_NOT_REGISTERED·REGISTRY_VERSION_CHANGED·
    // REGISTRY_UNAVAILABLE)은 아래 007 블록의 것을 그대로 쓴다 — 같은 등록표가 내는 같은 거절이라
    // 두 벌로 두면 화면이 코드로 분기할 때 갈라진다.
    VALIDATION_RULE_VIOLATED(
            HttpStatus.UNPROCESSABLE_ENTITY, "선택한 설정 조합은 실행할 수 없습니다. 안내를 확인해 주세요."),

    // spec 007 자동 파라미터 탐색. 계약은 specs/007-auto-parameter-search/contracts/param-search-api.md §6.
    CANDIDATE_LIMIT_EXCEEDED(
            HttpStatus.UNPROCESSABLE_ENTITY, "탐색할 후보가 한 번에 실행할 수 있는 수를 넘습니다. 범위를 좁혀 주세요."),
    NO_VALID_COMBINATION(HttpStatus.UNPROCESSABLE_ENTITY, "선택한 범위에 유효한 조합이 없습니다. 섹터나 범위를 바꿔 주세요."),
    UNREGISTERED_PARAMETER_VALUE(HttpStatus.BAD_REQUEST, "등록되지 않은 파라미터 값입니다."),
    STRATEGY_NOT_REGISTERED(HttpStatus.UNPROCESSABLE_ENTITY, "등록되지 않은 전략입니다."),
    STRATEGY_NOT_EXECUTABLE(HttpStatus.UNPROCESSABLE_ENTITY, "아직 실행을 지원하지 않는 전략입니다."),
    STRATEGY_NOT_SEARCHABLE(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "목표 비중을 직접 정하는 전략은 자동 탐색으로 비교할 수 없습니다. 분석하기에서 실행해 주세요."),
    REGISTRY_VERSION_CHANGED(HttpStatus.CONFLICT, "확인하신 사이에 전략 등록표가 바뀌었습니다. 바뀐 내용을 다시 확인해 주세요."),
    REGISTRY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "전략 등록 정보를 읽을 수 없습니다. 잠시 후 다시 시도해 주세요."),
    ALREADY_FINISHED(HttpStatus.CONFLICT, "이미 끝난 탐색입니다."),
    NOTHING_TO_RETRY(HttpStatus.CONFLICT, "다시 시도할 실패 후보가 없습니다."),

    // spec 013 배우기. 계약은 specs/013-learning-pages/contracts/README.md "새 오류 코드".
    // 공개 조회의 두 404(글·용어)를 한 코드로 합치지 않는다 — 화면이 '볼 수 없는 글'과
    // '설명 준비 중'을 코드로 가른다(research R10).
    LEARNING_ARTICLE_UNAVAILABLE(HttpStatus.NOT_FOUND, "지금은 볼 수 없는 글입니다."),
    LEARNING_TERM_NOT_READY(HttpStatus.NOT_FOUND, "설명을 준비하고 있습니다."),
    LEARNING_ARTICLE_NOT_FOUND(HttpStatus.NOT_FOUND, "관리할 글을 찾을 수 없습니다."),
    LEARNING_ARTICLE_ID_TAKEN(HttpStatus.CONFLICT, "이미 쓰이고 있는 글 식별자입니다."),
    LEARNING_TERM_ID_TAKEN(HttpStatus.CONFLICT, "다른 글이 쓰고 있는 용어 식별자입니다."),
    LEARNING_DRAFT_CHANGED(HttpStatus.CONFLICT, "다른 곳에서 초안이 바뀌었습니다. 새로 불러온 뒤 다시 고쳐 주세요."),
    LEARNING_PUBLISH_CHECK_FAILED(
            HttpStatus.UNPROCESSABLE_ENTITY, "게시 전 검사에 걸린 항목이 있어 게시하지 않았습니다."),

    // spec 006 내 기록. 계약은 specs/006-my-records/contracts/my-records-api.md "오류".
    // 저장 자리 한도(409)와 실행 자원 한도(EXECUTION_LIMIT, 429)를 다른 코드로 나눈다 — 사용자가
    // 할 일이 다르다. 앞은 기존 기록을 지워야 풀리고 뒤는 기다리면 풀린다(spec FR-018).
    RECORD_LIMIT_REACHED(HttpStatus.CONFLICT, "저장할 수 있는 탐색 묶음 개수에 도달했습니다. 기존 항목을 지운 뒤 다시 시작해 주세요."),
    RECORD_WITHDRAWN(HttpStatus.GONE, "더 볼 수 없는 검색 결과입니다."),

    // spec 015 화면 맥락 도우미. 계약은 specs/015-context-assistant/contracts/assistant-api.md §1.
    // 토큰 판정(400/404/409/410)은 005의 코드를 그대로 쓴다 — 같은 거절을 다른 코드로 내면 화면이 두 벌로 분기한다.
    ASSISTANT_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "질문 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."),
    ASSISTANT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 질문에 답할 수 없어요. 잠시 후 다시 시도해 주세요.");

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
