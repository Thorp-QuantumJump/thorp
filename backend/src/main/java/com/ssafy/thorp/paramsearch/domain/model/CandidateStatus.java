package com.ssafy.thorp.paramsearch.domain.model;

/**
 * 탐색 후보 상태. data-model.md §4.1의 전이만 허용한다.
 *
 * <pre>
 * PENDING ──▶ DISPATCHED ──▶ COMPLETED   결과 이벤트 status=COMPLETED
 *                        └─▶ FAILED      결과 이벤트 status=FAILED 또는 자료 부족
 * PENDING ──▶ SKIPPED                    취소·한도 도달로 발행되지 않음 ('미실행')
 * FAILED  ──▶ DISPATCHED                 사용자 요청 재시도. retryCount 증가 (FR-024)
 * (생성 시) ─▶ EXCLUDED                  유효성 위반으로 계산 전 제외 (FR-006)
 * PENDING ─▶ EXCLUDED                   발행하려 했더니 004 접수가 그 조합을 거부 (FR-006)
 * </pre>
 *
 * <p>{@code FAILED}와 {@code EXCLUDED}를 낮은 수익으로 표시하지 않는다(FR-011). 성과 값 자리를 0으로 채우지 않는다.
 *
 * <p>{@code SKIPPED}가 spec의 '미실행'이며 <b>되돌아오지 않는 확정 상태</b>다. 재시도는 {@code FAILED}에서만 출발한다 — 취소·한도로 빠진
 * 후보를 되살리면 그 묶음의 완료 범위 표시가 뜻을 잃는다.
 */
public enum CandidateStatus {
    PENDING,
    DISPATCHED,
    COMPLETED,
    FAILED,
    EXCLUDED,
    SKIPPED;

    /** 더 이상 바뀌지 않는 상태인가. {@code FAILED}는 재시도로 다시 발행될 수 있어 최종이 아니다. */
    public boolean isFinal() {
        return this == COMPLETED || this == EXCLUDED || this == SKIPPED;
    }

    /** 계산을 시도조차 하지 않은 상태인가. 이 후보들은 성과 값이 없고 사유만 남는다. */
    public boolean isUncomputed() {
        return this == EXCLUDED || this == SKIPPED;
    }

    /** {@code next}로 전이할 수 있는가. */
    public boolean canTransitionTo(CandidateStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            // EXCLUDED 로 가는 길이 둘이다 — 접수 전 유효성 판정과, 그것이 놓친 조합을 004 접수가
            // 거부한 경우다. 둘 다 "계산하지 않기로 확정"이라 같은 상태로 남긴다(FR-006).
            case PENDING -> next == DISPATCHED || next == SKIPPED || next == EXCLUDED;
            case DISPATCHED -> next == COMPLETED || next == FAILED;
            // 재시도(FR-024). SKIPPED는 재시도 대상이 아니다.
            case FAILED -> next == DISPATCHED;
            case COMPLETED, EXCLUDED, SKIPPED -> false;
        };
    }
}
