package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.LearningTermResult;

/** {@code GET /learning/terms/{termId}} 응답의 {@code data}(learning-public-api §4). */
public record LearningTermResponse(
        String termId, String label, String sentence, String articleId, String articleTitle) {

    public static LearningTermResponse from(LearningTermResult result) {
        return new LearningTermResponse(
                result.termId(),
                result.label(),
                result.sentence(),
                result.articleId(),
                result.articleTitle());
    }
}
