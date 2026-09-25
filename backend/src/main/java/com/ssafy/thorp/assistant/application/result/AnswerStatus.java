package com.ssafy.thorp.assistant.application.result;

/** 응답 {@code status} 넷(FR-010). {@code ANSWERED} 밖의 셋은 답이 고정 안내 문장이고 근거가 비어 있다. */
public enum AnswerStatus {
    ANSWERED,
    INSUFFICIENT_EVIDENCE,
    OUT_OF_SCOPE,
    /** 인사·잡담처럼 결과의 값을 묻지 않는 질문. 근거를 나열하지 않고 질문을 권한다. */
    GREETING
}
