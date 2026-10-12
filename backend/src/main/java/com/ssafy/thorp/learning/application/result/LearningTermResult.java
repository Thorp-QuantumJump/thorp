package com.ssafy.thorp.learning.application.result;

/**
 * {@code GET /learning/terms/{termId}}의 유스케이스 출력(learning-public-api §4).
 *
 * <p>{@code PublishedTermRepository}의 반환 타입이기도 하다. 용어 문장과 연결된 글의 제목은 같은 판에서 한 번에 읽어야 한다. 따로 읽어 조립하면
 * 두 값이 서로 다른 판을 가리킬 수 있다.
 *
 * @param sentence 연결된 글의 공개 중인 판 본문에 들어 있는 문장과 같다(FR-008)
 * @param articleId 도움말의 '해당 주제 글로 이동' 대상(FR-009)
 * @param articleTitle 공개 중인 판의 제목
 */
public record LearningTermResult(
        String termId, String label, String sentence, String articleId, String articleTitle) {}
