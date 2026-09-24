package com.ssafy.thorp.assistant.presentation.response;

import com.ssafy.thorp.assistant.application.result.AssistantAnswer;
import com.ssafy.thorp.assistant.application.result.Evidence;
import java.util.List;

/** {@code POST /assistant/questions} 200 본문(계약 §1, {@code examples/answer-*.json}). */
public record AssistantAnswerResponse(
        String status, String answer, List<EvidenceResponse> evidence, PageResponse page) {

    /** {@code value}는 표기 함수를 거친 문자열이고 {@code asOf}는 ISO 날짜 또는 null 이다. */
    public record EvidenceResponse(String id, String label, String value, String asOf, String tab) {
        static EvidenceResponse from(Evidence evidence) {
            return new EvidenceResponse(
                    evidence.id(),
                    evidence.label(),
                    evidence.formattedValue(),
                    evidence.asOf() == null ? null : evidence.asOf().toString(),
                    evidence.tab());
        }
    }

    public record PageResponse(String kind) {}

    public static AssistantAnswerResponse from(AssistantAnswer answer) {
        return new AssistantAnswerResponse(
                answer.status().name(),
                answer.answer(),
                answer.evidence().stream().map(EvidenceResponse::from).toList(),
                new PageResponse(answer.page().name()));
    }
}
