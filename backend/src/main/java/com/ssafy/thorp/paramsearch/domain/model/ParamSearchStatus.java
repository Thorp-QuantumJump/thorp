package com.ssafy.thorp.paramsearch.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 탐색 묶음 상태. data-model.md §3의 전이만 허용한다.
 *
 * <pre>
 * QUEUED ──▶ RUNNING ──▶ COMPLETED   모든 유효 후보가 완료 또는 실패로 끝남
 *                    ├─▶ PARTIAL     취소·한도 도달로 미실행 후보가 남음
 *                    └─▶ FAILED      접수 후 실행 자체가 불가 (기준 발행 실패 등)
 * QUEUED ──▶ REJECTED                접수 시점 거부 (상한 초과·유효 조합 0건)
 * </pre>
 *
 * <p>{@code REJECTED}는 저장하되 후보를 만들지 않는다. {@code PARTIAL}은 부분 결과임과 완료 범위를 표시하는 근거다(FR-009). {@code
 * COMPLETED}와 {@code PARTIAL} 둘 다 선정 계산을 수행한다 — 취소된 묶음도 완료된 후보로 비교할 수 있다.
 */
public enum ParamSearchStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    REJECTED;

    private static final Set<ParamSearchStatus> FINAL_STATUSES =
            EnumSet.of(COMPLETED, PARTIAL, FAILED, REJECTED);

    /** 더 이상 바뀌지 않는 상태인가. */
    public boolean isFinal() {
        return FINAL_STATUSES.contains(this);
    }

    /** 선정 계산 대상인가. 완료와 부분 완료 둘 다 계산한다. */
    public boolean isSelectable() {
        return this == COMPLETED || this == PARTIAL;
    }

    /** {@code next}로 전이할 수 있는가. 허용 목록에 없는 전이는 전부 거부다. */
    public boolean canTransitionTo(ParamSearchStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case QUEUED -> next == RUNNING || next == REJECTED;
            case RUNNING -> next == COMPLETED || next == PARTIAL || next == FAILED;
            case COMPLETED, PARTIAL, FAILED, REJECTED -> false;
        };
    }
}
