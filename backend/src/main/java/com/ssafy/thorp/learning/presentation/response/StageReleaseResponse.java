package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.StageReleaseResult;
import java.time.Instant;
import java.util.List;

/**
 * {@code POST /admin/learning/stages/{stageKey}/release} 200의 {@code data}(learning-admin-api §11,
 * 예시 {@code stage-release.json}).
 */
public record StageReleaseResponse(
        String stageKey,
        Instant releasedAt,
        List<Published> published,
        List<PublishResponse.TransferredTerm> transferredTerms,
        String logEntryKey,
        String checkVersion) {

    public static StageReleaseResponse from(StageReleaseResult result) {
        return new StageReleaseResponse(
                result.stageKey(),
                result.releasedAt(),
                result.published().stream()
                        .map(
                                article ->
                                        new Published(
                                                article.articleId(),
                                                article.revisionNo(),
                                                article.renamedQuestions().stream()
                                                        .map(PublishResponse.RenamedQuestion::from)
                                                        .toList()))
                        .toList(),
                result.transferredTerms().stream()
                        .map(PublishResponse.TransferredTerm::from)
                        .toList(),
                result.logEntryKey(),
                result.checkVersion());
    }

    /**
     * @param renamedQuestions 같은 문제 판정으로 새 식별자를 받은 문제. 관리 화면은 이 탭에 보관한 그 글의 편집 내용을 이 값대로 고친다
     */
    public record Published(
            String articleId,
            int revisionNo,
            List<PublishResponse.RenamedQuestion> renamedQuestions) {}
}
