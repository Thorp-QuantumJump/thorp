package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.domain.model.LearningStage;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.util.List;
import java.util.Optional;

/**
 * 고정 단계({@code learning_stage}, data-model §3-1). 단계 정의는 마이그레이션만 만들고 바꾼다. 관리 API에 정의를 바꾸는 경로가 없다
 * (FR-023).
 */
public interface LearningStageRepository {

    /** 여섯 단계 전부. 번호 순서다. */
    List<LearningStage> findAllOrdered();

    Optional<LearningStage> findByStageKey(StageKey stageKey);

    /** 공개된 단계가 하나라도 있는가. 없으면 기존 글이 첫 단계 공개 전 모습({@code PRE_RELEASE})으로 보인다(FR-031). */
    boolean anyReleased();
}
