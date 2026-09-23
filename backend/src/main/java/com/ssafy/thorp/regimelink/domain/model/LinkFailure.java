package com.ssafy.thorp.regimelink.domain.model;

import java.util.Objects;

/**
 * 계산이 실패한 사실 (data-model §4 {@code failure}).
 *
 * <p><b>자료 부족은 여기 오지 않는다.</b> {@code DATA_INSUFFICIENT}는 판정이지 실패가 아니고, 그 링크는 결과 없이 {@code
 * COMPLETED}로 끝난다(FR-018, research R13).
 */
public record LinkFailure(String code, String message) {

    /** 브로커가 재전달 상한을 다 쓰고 DLQ 로 보낸 작업. 계산이 실패한 것이 아니라 소화되지 못한 것이다. */
    public static final String SYSTEM_FAILURE = "SYSTEM_FAILURE";

    /** 링크가 기다리던 004 실행이 실패하거나 취소됐다. 계산은 시작되지도 않았다. */
    public static final String RUN_FAILED = "RUN_FAILED";

    /** 구간 입력이 계약 상한을 넘어 발행하지 않았다. 같은 입력으로 다시 보내도 결과가 같다. */
    public static final String INPUT_TOO_LARGE = "INPUT_TOO_LARGE";

    private static final String INPUT_INCOMPLETE = "INPUT_INCOMPLETE";
    private static final String MARKET_DATA_MISSING = "MARKET_DATA_MISSING";

    public LinkFailure {
        Objects.requireNonNull(code, "failure code must not be null");
    }

    /**
     * 화면에 '다시 시도'를 둘 수 있는가 (research R13, contracts §3).
     *
     * <p>{@code INPUT_INCOMPLETE}·{@code MARKET_DATA_MISSING}은 한 번은 일시적일 수 있지만, 두 번째 시도에서도 같은 코드가
     * 나오면 자료 쪽 문제라 다시 눌러도 같은 결과다 — 그때부터 버튼을 없앤다.
     *
     * <p>{@code INPUT_TOO_LARGE}는 백엔드가 발행 전에 판정한 것이라 시도 횟수와 무관하게 결정적이다.
     *
     * @param attempt 이 실패를 낸 발행 시도 번호
     */
    public boolean retryable(int attempt) {
        if (INPUT_TOO_LARGE.equals(code)) {
            return false;
        }
        if (INPUT_INCOMPLETE.equals(code) || MARKET_DATA_MISSING.equals(code)) {
            return attempt < 2;
        }
        return true;
    }
}
