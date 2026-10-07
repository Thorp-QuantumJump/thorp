package com.ssafy.thorp.learning.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 배지 하나의 지금 상태(data-model §12-4). 받은 배지는 {@code EARNED}다. 받지 않은 배지는 지금 공개된 콘텐츠로 받을 수 없으면 {@code
 * PREPARING}(사유와 함께, 진행은 보이지 않는다), 아니면 {@code NOT_YET}(기준과 진행)이다.
 *
 * @param earnedAt 받은 시각. 받지 않았으면 null
 * @param progress {@code NOT_YET}의 진행. 진행이 없는 기준({@code persistence})과 다른 상태는 null
 * @param reason {@code PREPARING}의 사유. 다른 상태는 null
 */
public record BadgeStatus(
        BadgeDefinition definition,
        State state,
        Instant earnedAt,
        Progress progress,
        PreparingReason reason) {

    public BadgeStatus {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if ((state == State.EARNED) != (earnedAt != null)) {
            throw new IllegalArgumentException("only an earned badge has earnedAt");
        }
        if ((state == State.PREPARING) != (reason != null)) {
            throw new IllegalArgumentException("only a preparing badge has a reason");
        }
        if (progress != null && state != State.NOT_YET) {
            throw new IllegalArgumentException("only a not-yet badge shows progress");
        }
    }

    public enum State {
        EARNED,
        NOT_YET,
        PREPARING
    }

    /** 진행 n/m. 레벨 배지는 경험치와 그 레벨의 문턱, 단계 완주는 그 단계의 완료 수와 공개된 글 수, 그 밖은 지금의 수와 기준 수다. */
    public record Progress(int current, int target) {}
}
