package com.ssafy.thorp.learning.application.result;

/**
 * 새 기준 판({@code CURRENT})이 게시 중인 글 하나의 읽는 순서 정보(data-model §12-1·§12-2). 글 번호·다음 글·단계의 공개된 글 수를 정할
 * 때 쓴다.
 *
 * @param title 공개 중인 판의 제목
 * @param stageNumber 공개 배치 단계의 번호
 * @param position 공개 배치의 단계 안 순서
 */
public record CurrentArticleEntry(
        String articleId, String title, String stageKey, int stageNumber, int position) {}
