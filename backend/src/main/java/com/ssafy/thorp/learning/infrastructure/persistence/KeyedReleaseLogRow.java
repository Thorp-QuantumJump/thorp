package com.ssafy.thorp.learning.infrastructure.persistence;

/**
 * 단계 키와 글 키를 붙인 변경 기록 한 줄({@code LearningReleaseLogJpaRepository}의 JPQL 생성자 식).
 *
 * @param articleKey {@code ARTICLE_ADDED}일 때만 있다
 */
public record KeyedReleaseLogRow(
        String stageKey, String articleKey, LearningReleaseLogJpaEntity log) {}
