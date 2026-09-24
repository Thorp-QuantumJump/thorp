package com.ssafy.thorp.assistant.presentation.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code POST /assistant/questions} 본문(계약 §1).
 *
 * <p>Bean Validation 을 붙이지 않는다 — 길이·종류·식별자 검증은 서비스가 플래그 판정 뒤에 한다. 기능이 꺼진 환경에서 400 이 먼저 나가면 경로가 있다는
 * 사실이 샌다. {@code context}의 모르는 키는 {@code RenameMyRecordRequest}처럼 받아 두고 서비스가 거절한다(식별자는 종류당 하나).
 */
public record AssistantQuestionRequest(String question, Context context) {

    public record Context(
            String kind, String publicToken, @JsonAnySetter Map<String, Object> extra) {
        public Context {
            extra = extra == null ? new LinkedHashMap<>() : extra;
        }
    }
}
