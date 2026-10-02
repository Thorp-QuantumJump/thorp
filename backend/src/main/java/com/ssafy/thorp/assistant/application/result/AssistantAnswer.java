package com.ssafy.thorp.assistant.application.result;

import java.util.List;

/**
 * 검증·치환을 끝낸 답(spec 015 Key Entities, 017 FR-010).
 *
 * @param answer 자리표시자가 치환된 최종 문장. 안의 숫자·날짜는 모두 {@code evidence}의 값에서 왔다
 * @param evidence 답에 실제로 쓰인 근거만
 * @param relatedArticles 쓰인 근거의 배우기 글. 중복 없이 처음 쓰인 순서, 최대 3개. 결과 화면은 항상 비어 있다
 */
public record AssistantAnswer(
        AnswerStatus status,
        String answer,
        List<Evidence> evidence,
        PageKind page,
        List<RelatedArticle> relatedArticles) {

    public AssistantAnswer(
            AnswerStatus status, String answer, List<Evidence> evidence, PageKind page) {
        this(status, answer, evidence, page, List.of());
    }
}
