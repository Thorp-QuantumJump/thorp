package com.ssafy.thorp.assistant.application.port;

import com.ssafy.thorp.assistant.application.exception.ToolUnavailableException;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import java.util.List;
import java.util.Map;

/**
 * 서버가 실행하는 읽기 도구(FR-007). 요청 시작 때 풀린 결과에 묶여 만들어지므로 인자에 식별자가 없다.
 *
 * <p>{@link #run}은 시계열·거래·보유에서 <b>고르기만</b> 한다. 합계·비율·구간을 새로 계산하지 않는다(아키텍처 원칙 1).
 */
public interface AssistantTool {
    ToolSpec spec();

    /**
     * 인자 이름·날짜 형식은 루프가 먼저 검증한다. 여기서는 결과 기간 밖·규칙 위반(구간 1년 초과 등)을 {@link ToolUnavailableException}으로
     * 알린다 — 그러면 실행하지 않은 것으로 모델에 돌아간다.
     */
    List<Evidence> run(Map<String, String> arguments);
}
