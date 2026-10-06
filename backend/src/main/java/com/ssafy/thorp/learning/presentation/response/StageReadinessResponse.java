package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.StageReadinessResult;
import com.ssafy.thorp.learning.domain.exception.PublishCheckFailedException;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /admin/learning/stages/{stageKey}/readiness}의 {@code data}(learning-admin-api §10, 예시
 * {@code stage-readiness.json}).
 *
 * <p>글마다의 항목은 게시 422({@code LEARNING_PUBLISH_CHECK_FAILED})의 {@code failures} 항목과 같은 모양이다. 같은 변환을
 * 쓴다.
 */
public record StageReadinessResponse(
        String stageKey,
        boolean ready,
        List<String> blockers,
        List<Article> articles,
        String checkVersion) {

    public static StageReadinessResponse from(StageReadinessResult result) {
        return new StageReadinessResponse(
                result.stageKey(),
                result.ready(),
                result.blockers().stream().map(Enum::name).toList(),
                result.articles().stream()
                        .map(
                                article ->
                                        new Article(
                                                article.articleId(),
                                                article.draftVersion(),
                                                article.failures().stream()
                                                        .map(PublishCheckFailedException::toDetail)
                                                        .toList()))
                        .toList(),
                result.checkVersion());
    }

    /**
     * @param draftVersion 화면이 단계 공개 요청에 그대로 싣는다
     */
    public record Article(String articleId, int draftVersion, List<Map<String, Object>> failures) {}
}
