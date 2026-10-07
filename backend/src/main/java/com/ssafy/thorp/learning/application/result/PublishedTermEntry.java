package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.Objects;

/**
 * 공개 용어 하나(게시 용어 색인 한 행). 나의 학습이 공개된 용어 수와 익힌 용어 목록을 만들 때 쓴다(data-model §12-1).
 *
 * @param label 공개 판의 용어 이름
 * @param articleId 이 용어를 공개 중인 글
 */
public record PublishedTermEntry(TermId termId, String label, ArticleId articleId) {

    public PublishedTermEntry {
        Objects.requireNonNull(termId, "termId must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(articleId, "articleId must not be null");
    }
}
