package com.ssafy.thorp.assistant.presentation.response;

import com.ssafy.thorp.assistant.application.result.AssistantAnswer;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceSource;
import java.util.List;

/**
 * {@code POST /assistant/questions} 200 본문(015·017 계약 §1, {@code examples/answer-*.json}·{@code
 * term-answer-*.json}).
 */
public record AssistantAnswerResponse(
        String status,
        String answer,
        List<EvidenceResponse> evidence,
        List<RelatedArticleResponse> relatedArticles,
        PageResponse page) {

    /**
     * {@code value}는 표기 함수를 거친 문자열이고 {@code asOf}는 ISO 날짜 또는 null 이다. {@code tab}·{@code source}는
     * 결과 근거면 탭 id·null, 용어 근거면 null·출처다.
     */
    public record EvidenceResponse(
            String id, String label, String value, String asOf, String tab, SourceResponse source) {
        static EvidenceResponse from(Evidence evidence) {
            return new EvidenceResponse(
                    evidence.id(),
                    evidence.label(),
                    evidence.formattedValue(),
                    evidence.asOf() == null ? null : evidence.asOf().toString(),
                    evidence.tab(),
                    SourceResponse.from(evidence.source()));
        }
    }

    public record SourceResponse(String type, String articleId, String articleTitle) {
        static SourceResponse from(EvidenceSource source) {
            return source == null
                    ? null
                    : new SourceResponse(
                            source.type().name(), source.articleId(), source.articleTitle());
        }
    }

    public record RelatedArticleResponse(String articleId, String title) {}

    public record PageResponse(String kind) {}

    public static AssistantAnswerResponse from(AssistantAnswer answer) {
        return new AssistantAnswerResponse(
                answer.status().name(),
                answer.answer(),
                answer.evidence().stream().map(EvidenceResponse::from).toList(),
                answer.relatedArticles().stream()
                        .map(
                                article ->
                                        new RelatedArticleResponse(
                                                article.articleId(), article.title()))
                        .toList(),
                new PageResponse(answer.page().name()));
    }
}
