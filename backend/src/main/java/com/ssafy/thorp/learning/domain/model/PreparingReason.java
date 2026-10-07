package com.ssafy.thorp.learning.domain.model;

/**
 * 지금 공개된 콘텐츠로는 닿을 수 없는 레벨·배지의 사유(data-model §12-3·§12-4). 화면이 design-notes §7의 문구로 바꾼다.
 *
 * <p>커리큘럼·나의 학습의 단계 상태 {@code PREPARING}(단계 공개 전)과는 다른 값이다. 단계가 공개된 뒤에도 배지는 준비 중일 수 있다.
 */
public enum PreparingReason {
    /** 공개되지 않은 단계가 있다. '단계가 더 공개된 뒤' */
    MORE_STAGES,
    /** 여섯 단계를 모두 공개했지만 공개된 글이 모자란다. '글이 더 공개된 뒤' */
    MORE_ARTICLES,
    /** '읽은 글 27편'·'그때 게시된 용어 전체'·'탐험'은 여섯 단계를 모두 공개한 뒤 판정한다 */
    ALL_STAGES,
    /** 그 단계가 공개 전이다 */
    STAGE_NOT_RELEASED,
    /** 그 단계는 공개됐지만 공개된 글이 0편이다 */
    STAGE_EMPTY;

    /** 수를 세는 기준과 레벨의 사유. 공개되지 않은 단계가 있으면 {@link #MORE_STAGES}, 없으면 {@link #MORE_ARTICLES}다. */
    public static PreparingReason forCounts(boolean allStagesReleased) {
        return allStagesReleased ? MORE_ARTICLES : MORE_STAGES;
    }
}
