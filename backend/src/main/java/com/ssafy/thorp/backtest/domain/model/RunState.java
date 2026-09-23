package com.ssafy.thorp.backtest.domain.model;

import java.util.Objects;

/**
 * 실행의 변할 수 있는 부분. {@link BacktestRun}의 나머지 필드(요청 원문)는 만든 뒤 바뀌지 않으므로, 이벤트로 갱신되는 값만 묶어 복원·전이의 인자 수를
 * 줄인다.
 *
 * @param attempt STARTED를 받은 횟수. 발행 시도 수보다 크면 자동 재시도가 일어난 것이다(R-06·R-18)
 * @param currentExecutionId 마지막 STARTED의 엔진 실행 시도 식별자. FAILED 적용 여부를 이 값으로 가른다(R-18)
 * @param partial true = 취소·한도로 끊긴 부분 결과
 * @param progress null = 아직 STARTED를 받지 않았다
 * @param failureCode null = 실패하지 않았다
 */
public record RunState(
        RunStatus status,
        int attempt,
        String currentExecutionId,
        boolean partial,
        RunProgress progress,
        String failureCode,
        String failureMessage) {

    public RunState {
        Objects.requireNonNull(status, "status must not be null");
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative: " + attempt);
        }
        if (partial && status != RunStatus.CANCELLED) {
            throw new IllegalArgumentException("partial is only for CANCELLED runs: " + status);
        }
    }

    /** 아직 아무 이벤트도 받지 않은 상태. 새 실행과 상태만 아는 복원에 쓴다. */
    public static RunState of(RunStatus status) {
        return new RunState(status, 0, null, false, null, null, null);
    }
}
