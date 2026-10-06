package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.application.result.PublishResult.RenamedQuestion;
import com.ssafy.thorp.learning.application.result.PublishResult.TransferredTerm;
import java.time.Instant;
import java.util.List;

/**
 * 단계 공개 결과(learning-admin-api §11 200).
 *
 * @param releasedAt 단계 공개일. 이 공개가 쓴 판·기록·변경 기록이 모두 같은 시각이다
 * @param published 게시한 글, 단계 안 순서
 * @param transferredTerms 넘겨받은 용어. 글마다 게시 응답과 같은 모양이고 {@code toArticleId}로 글을 가른다
 * @param logEntryKey 이 공개가 쓴 변경 기록 한 줄
 */
public record StageReleaseResult(
        String stageKey,
        Instant releasedAt,
        List<PublishedArticle> published,
        List<TransferredTerm> transferredTerms,
        String logEntryKey,
        String checkVersion) {

    public StageReleaseResult {
        published = List.copyOf(published);
        transferredTerms = List.copyOf(transferredTerms);
    }

    /**
     * @param renamedQuestions 같은 문제 판정으로 새 식별자를 받은 문제. 초안에서도 새 식별자가 됐다
     */
    public record PublishedArticle(
            String articleId, int revisionNo, List<RenamedQuestion> renamedQuestions) {

        public PublishedArticle {
            renamedQuestions = List.copyOf(renamedQuestions);
        }
    }
}
