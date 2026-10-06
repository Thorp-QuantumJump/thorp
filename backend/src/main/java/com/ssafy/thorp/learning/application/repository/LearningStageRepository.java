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

    /** 행을 잠그고 <b>잠근 시점의 값을 다시 읽는다</b>. 같은 단계를 두 요청이 동시에 공개하면 뒤엣것은 앞엣것이 커밋한 공개일을 본다(R23). */
    Optional<LearningStage> findByStageKeyForUpdate(StageKey stageKey);

    /** 공개일과 공개한 계정을 쓴다. 정의 열은 바꾸지 않는다. 잠근 행에만 쓴다. */
    void saveRelease(LearningStage stage);

    /** 공개된 단계가 하나라도 있는가. 없으면 기존 글이 첫 단계 공개 전 모습({@code PRE_RELEASE})으로 보인다(FR-031). */
    boolean anyReleased();
}
