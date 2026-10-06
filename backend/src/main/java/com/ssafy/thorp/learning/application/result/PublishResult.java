package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.ArticleStatus;
import com.ssafy.thorp.learning.domain.model.Edition;
import java.time.Instant;
import java.util.List;

/**
 * 게시 결과(learning-admin-api §6 200).
 *
 * @param checkVersion 통과한 게시 전 검사 규칙 버전
 * @param sectorMapVersion 게시 검사 때 대조한 001 대응표 버전. 예시가 없는 V2 판은 {@code NOT_CHECKED}다
 * @param edition 새 판의 종류
 * @param renamedQuestions 같은 문제 판정으로 새 식별자를 받은 문제(data-model §6-3). 초안에서도 새 식별자가 됐다
 * @param transferredTerms 넘겨받은 용어(R24). 넘겨받기는 B08이 채운다
 */
public record PublishResult(
        String articleId,
        ArticleStatus status,
        int revisionNo,
        String checkVersion,
        String sectorMapVersion,
        Instant publishedAt,
        Edition edition,
        List<RenamedQuestion> renamedQuestions,
        List<TransferredTerm> transferredTerms) {

    public PublishResult {
        renamedQuestions = List.copyOf(renamedQuestions);
        transferredTerms = List.copyOf(transferredTerms);
    }

    public record RenamedQuestion(String from, String to) {}

    /** {@code toArticleId}는 게시한 글이다. 단계 공개 응답과 같은 모양이다(learning-admin-api §6·§11). */
    public record TransferredTerm(String termId, String fromArticleId, String toArticleId) {}
}
