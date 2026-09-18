package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.LearningArticleSummaryResult;
import java.util.List;

/** {@code GET /learning/articles} 응답의 {@code data}(learning-public-api §1). */
public record LearningArticleListResponse(List<Item> articles) {

    public static LearningArticleListResponse from(List<LearningArticleSummaryResult> results) {
        return new LearningArticleListResponse(
                results.stream()
                        .map(
                                result ->
                                        new Item(
                                                result.articleId(),
                                                result.title(),
                                                result.question(),
                                                result.readingOrder()))
                        .toList());
    }

    public record Item(String articleId, String title, String question, int readingOrder) {}
}
