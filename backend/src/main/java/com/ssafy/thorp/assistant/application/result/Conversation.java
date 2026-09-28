package com.ssafy.thorp.assistant.application.result;

import java.util.List;

/**
 * 모델에 보내는 이력. {@code store:false}라 라운드마다 이전 도구 요청·결과를 서버가 다시 담아 보낸다(계약 §2.1).
 *
 * <p>여기에는 질문·도구 요청·도구 결과만 있다. 토큰·{@code backtestId}·계정·화면 상태는 어느 자리에도 없다(FR-008).
 */
public record Conversation(String question, List<Exchange> exchanges) {

    /** 도구 라운드 하나: 모델이 요청한 호출들과 서버가 돌려준 결과들. */
    public record Exchange(List<ToolCall> calls, List<ToolResult> results) {}
}
