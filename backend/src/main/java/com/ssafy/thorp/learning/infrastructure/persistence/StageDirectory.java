package com.ssafy.thorp.learning.infrastructure.persistence;

import org.springframework.stereotype.Component;

/**
 * 단계 대리키 ↔ {@code stageKey}를 처음 한 번 읽어 둔다. 단계 행은 마이그레이션만 만들고 지우지 않으며({@link
 * LearningStageJpaRepository}) 대리키와 키는 바뀌지 않는다. 바뀌는 공개 열({@code released_at})은 여기 담지 않는다.
 *
 * <p>저장소 호출마다 단계 표를 다시 읽으면 단계 공개 한 번에 같은 여섯 행을 수십 번 읽는다.
 */
@Component
public class StageDirectory {

    private final LearningStageJpaRepository stageJpaRepository;
    private volatile StageLookup lookup;

    public StageDirectory(LearningStageJpaRepository stageJpaRepository) {
        this.stageJpaRepository = stageJpaRepository;
    }

    /** 두 스레드가 처음에 함께 읽으면 둘 다 읽지만 결과가 같다. */
    StageLookup lookup() {
        StageLookup loaded = lookup;
        if (loaded == null) {
            loaded = StageLookup.load(stageJpaRepository);
            lookup = loaded;
        }
        return loaded;
    }
}
