package com.ssafy.thorp.assistant.application.port;

import com.ssafy.thorp.assistant.application.result.Conversation;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.ModelTurn;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import java.time.Duration;
import java.util.List;

/**
 * 외부 모델 한 번 호출(ADR-0008). 구현은 {@code infrastructure/client}에 있고, 제공업체나 API 형식을 바꾸면 구현만 바꾼다.
 *
 * <p>구현이 지켜야 할 것: 입력은 질문·이력·근거 팩·도구 목록뿐이다. 장애·시간 초과·응답 스키마 불일치는 {@code ASSISTANT_UNAVAILABLE}
 * {@code BusinessException}으로 던진다. 도구 인자가 JSON이 아니면 빈 인자가 아니라 검증에 걸리는 값으로 넘긴다.
 */
public interface AssistantModelPort {
    /**
     * 모델을 한 번 호출해 도구 요청이나 최종 초안 중 하나를 받는다.
     *
     * @param timeout 이 호출에 쓸 수 있는 시간. 전체 데드라인의 남은 시간과 라운드 예산 중 작은 쪽이다
     */
    ModelTurn step(
            Conversation conversation,
            List<Evidence> facts,
            List<ToolSpec> tools,
            Duration timeout);
}
