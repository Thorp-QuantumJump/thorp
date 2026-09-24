package com.ssafy.thorp.assistant.application.result;

import java.util.List;

/**
 * 검증·치환을 끝낸 답(spec 015 Key Entities).
 *
 * @param answer 자리표시자가 치환된 최종 문장. 안의 숫자·날짜는 모두 {@code evidence}의 값에서 왔다
 * @param evidence 답에 실제로 쓰인 근거만
 */
public record AssistantAnswer(
        AnswerStatus status, String answer, List<Evidence> evidence, PageKind page) {}
