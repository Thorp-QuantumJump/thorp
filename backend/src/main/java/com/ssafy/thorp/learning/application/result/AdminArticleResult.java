package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.LearningArticle;
import java.util.Objects;

/**
 * 관리 화면이 읽는 초안 하나(learning-admin-api §4). 만들기·저장 응답도 같은 모양이다.
 *
 * @param article 초안 작업본과 그 글의 초안 용어
 * @param publishedRevisionNo 공개 중인 판 번호. 초안이면 null이다
 */
public record AdminArticleResult(LearningArticle article, Integer publishedRevisionNo) {

    public AdminArticleResult {
        Objects.requireNonNull(article, "article must not be null");
    }
}
