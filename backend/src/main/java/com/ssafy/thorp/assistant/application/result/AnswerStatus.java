package com.ssafy.thorp.assistant.application.result;

/** 응답 {@code status} 셋(FR-010). 뒤 둘은 답이 고정 안내 문장이고 근거가 비어 있다. */
public enum AnswerStatus {
    ANSWERED,
    INSUFFICIENT_EVIDENCE,
    OUT_OF_SCOPE
}
