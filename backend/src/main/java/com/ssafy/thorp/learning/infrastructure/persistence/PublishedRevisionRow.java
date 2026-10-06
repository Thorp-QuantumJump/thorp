package com.ssafy.thorp.learning.infrastructure.persistence;

/**
 * 공개 중인 판과 그 글의 키·공개 배치를 한 문장으로 읽은 행({@code LearningArticleRevisionJpaRepository}의 JPQL 생성자 식). 값이
 * 모두 같은 순간의 것이라 게시 취소·다시 게시와 겹쳐도 어긋나지 않는다.
 *
 * @param publishedStageId 게시 중인 글이면 테이블 CHECK가 채워 둔다
 * @param publishedStagePosition 게시 중인 글이면 테이블 CHECK가 채워 둔다
 */
public record PublishedRevisionRow(
        String articleKey,
        LearningArticleRevisionJpaEntity revision,
        Long publishedStageId,
        Short publishedStagePosition) {}
