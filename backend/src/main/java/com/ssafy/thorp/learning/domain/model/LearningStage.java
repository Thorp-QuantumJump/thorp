package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.time.Instant;
import java.util.Objects;

/**
 * 고정 단계 하나(data-model §3-1, research R14). 번호·이름·수준 표시·한 줄 설명은 마이그레이션만 바꾼다.
 *
 * <p><b>불변식</b>: 공개일과 공개한 계정은 함께 있거나 함께 없다. 같은 관계를 테이블 CHECK도 건다. 공개는 되돌리지 않는다(FR-031).
 *
 * @param id DB 대리키. 응답에 내보내지 않는다
 * @param releasedAt 단계를 공개한 시각. 공개 전이면 null
 * @param releasedByUserId 공개한 관리 계정. 공개 전이면 null
 */
public record LearningStage(
        Long id,
        StageKey stageKey,
        int number,
        String name,
        String levelLabel,
        String summary,
        Instant releasedAt,
        Long releasedByUserId) {

    public LearningStage {
        Objects.requireNonNull(stageKey, "stageKey must not be null");
        Objects.requireNonNull(name, "stage name must not be null");
        Objects.requireNonNull(levelLabel, "stage levelLabel must not be null");
        Objects.requireNonNull(summary, "stage summary must not be null");
        if (number < 1) {
            throw new IllegalArgumentException("stage number must be >= 1: " + number);
        }
        if ((releasedAt == null) != (releasedByUserId == null)) {
            throw new IllegalArgumentException(
                    "releasedAt and releasedByUserId must be present together: " + stageKey);
        }
    }

    public boolean released() {
        return releasedAt != null;
    }

    /**
     * 단계를 공개한다. 공개일은 그 공개 트랜잭션의 시각 하나다(R23).
     *
     * @throws IllegalStateException 이미 공개했다. 공개는 되돌리거나 다시 쓰지 않는다. 유스케이스가 먼저 409로 막는다
     */
    public LearningStage release(Instant at, long userId) {
        if (released()) {
            throw new IllegalStateException("stage already released: " + stageKey);
        }
        return new LearningStage(id, stageKey, number, name, levelLabel, summary, at, userId);
    }
}
