package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.util.HashMap;
import java.util.Map;

/**
 * 단계 대리키 ↔ {@code stageKey}. 단계는 여섯 행뿐이라 저장소 호출마다 한 번에 읽어 둔다. 글 행은 단계를 대리키로 가리키고 도메인은 고정 식별자로 가리킨다.
 */
final class StageLookup {

    private final Map<Long, StageKey> keysById = new HashMap<>();
    private final Map<StageKey, Long> idsByKey = new HashMap<>();

    private StageLookup() {}

    static StageLookup load(LearningStageJpaRepository repository) {
        StageLookup lookup = new StageLookup();
        for (LearningStageJpaEntity stage : repository.findAllByOrderByNumberAsc()) {
            StageKey key = new StageKey(stage.getStageKey());
            lookup.keysById.put(stage.getId(), key);
            lookup.idsByKey.put(key, stage.getId());
        }
        return lookup;
    }

    /**
     * @return 대리키가 null이면 null
     * @throws IllegalStateException 외래키가 가리키는 단계가 없다. 스키마가 막으므로 저장 데이터가 깨진 것이다
     */
    StageKey keyOf(Long stageId) {
        if (stageId == null) {
            return null;
        }
        StageKey key = keysById.get(stageId);
        if (key == null) {
            throw new IllegalStateException("learning stage not found: id=" + stageId);
        }
        return key;
    }

    /**
     * @throws IllegalArgumentException 없는 단계. 유스케이스가 먼저 존재를 확인하므로 여기 오면 프로그래밍 오류다
     */
    Long idOf(StageKey stageKey) {
        if (stageKey == null) {
            return null;
        }
        Long id = idsByKey.get(stageKey);
        if (id == null) {
            throw new IllegalArgumentException("learning stage not found: " + stageKey);
        }
        return id;
    }
}
