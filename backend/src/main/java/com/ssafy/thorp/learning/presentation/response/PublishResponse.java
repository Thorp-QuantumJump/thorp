package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.PublishResult;
import java.time.Instant;
import java.util.List;

/** 게시 200의 {@code data}(learning-admin-api §6, 예시 {@code publish-success.json}). */
public record PublishResponse(
        String articleId,
        String status,
        int revisionNo,
        String checkVersion,
        String sectorMapVersion,
        Instant publishedAt,
        String edition,
        List<RenamedQuestion> renamedQuestions,
        List<TransferredTerm> transferredTerms) {

    public static PublishResponse from(PublishResult result) {
        return new PublishResponse(
                result.articleId(),
                result.status().name(),
                result.revisionNo(),
                result.checkVersion(),
                result.sectorMapVersion(),
                result.publishedAt(),
                result.edition().name(),
                result.renamedQuestions().stream()
                        .map(renamed -> new RenamedQuestion(renamed.from(), renamed.to()))
                        .toList(),
                result.transferredTerms().stream()
                        .map(
                                term ->
                                        new TransferredTerm(
                                                term.termId(),
                                                term.fromArticleId(),
                                                term.toArticleId()))
                        .toList());
    }

    /** 같은 문제 판정으로 새 식별자를 받은 문제. 이미 답한 사용자에게도 새 문제로 보인다. */
    public record RenamedQuestion(String from, String to) {}

    public record TransferredTerm(String termId, String fromArticleId, String toArticleId) {}
}
