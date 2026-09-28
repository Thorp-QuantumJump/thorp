package com.ssafy.thorp.assistant.application.result;

import java.util.List;

/**
 * 모델의 최종 답변 초안(계약 §2.3 스키마). 검증 전 상태라 그대로 화면에 가지 않는다.
 *
 * @param parts 문장 조각. {@code text}의 값 자리는 {@code {{Fn}}}뿐이고 {@code factIds}가 그 목록이다
 */
public record ModelDraft(AnswerStatus status, List<AnswerPart> parts) implements ModelTurn {

    public record AnswerPart(String text, List<String> factIds) {}
}
