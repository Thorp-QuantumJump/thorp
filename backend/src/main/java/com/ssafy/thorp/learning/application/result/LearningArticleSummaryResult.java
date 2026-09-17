package com.ssafy.thorp.learning.application.result;

/**
 * 공개 글 목록의 한 항목(learning-public-api §1). 값은 공개 중인 판에서 읽는다.
 *
 * @param readingOrder 읽는 순서 제안(FR-001). 화면은 이 순서로 보이되 강제하지 않는다
 */
public record LearningArticleSummaryResult(
        String articleId, String title, String question, int readingOrder) {}
