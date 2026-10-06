package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.util.Objects;

/**
 * 공개 중인 글 하나의 커리큘럼 항목. 판에서 제목·질문·읽기 분·예시 유무만 꺼내고 본문은 읽지 않는다(learning-public-api §1).
 *
 * @param stageKey 공개 배치의 단계
 * @param position 공개 배치의 단계 안 순서
 * @param readingMinutes 판에 적힌 읽기 분. {@code CURRENT} 판에만 있고 {@code LEGACY} 판은 null이다
 * @param hasExample 판에 예시가 있다
 */
public record PublishedArticleEntry(
        ArticleId articleId,
        Edition edition,
        StageKey stageKey,
        int position,
        String title,
        String question,
        Integer readingMinutes,
        boolean hasExample) {

    public PublishedArticleEntry {
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(edition, "edition must not be null");
        Objects.requireNonNull(stageKey, "stageKey must not be null");
        if (edition == Edition.CURRENT) {
            Objects.requireNonNull(readingMinutes, "a CURRENT revision has reading minutes");
        }
    }
}
