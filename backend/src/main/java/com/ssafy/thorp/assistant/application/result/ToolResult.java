package com.ssafy.thorp.assistant.application.result;

import java.util.List;

/**
 * 도구 호출 하나의 결과. 실행했으면 더해진 근거, 실행하지 않았으면 "사용할 수 없음"의 이유다(FR-007).
 *
 * @param facts 팩에 실제로 더해진 근거(id가 매겨진 뒤). 실행하지 않았으면 빈 목록
 * @param unavailableReason 실행하지 않은 이유. 실행했으면 null
 */
public record ToolResult(String callId, List<Evidence> facts, String unavailableReason) {

    public static ToolResult unavailable(String callId, String reason) {
        return new ToolResult(callId, List.of(), reason);
    }

    public boolean unavailable() {
        return unavailableReason != null;
    }
}
