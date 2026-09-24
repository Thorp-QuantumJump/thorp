package com.ssafy.thorp.assistant.application.result;

import java.util.Map;

/**
 * 모델이 응답에 적은 "이 도구를 이 인자로" 요청. 실행은 서버가 이름·인자를 검증한 뒤 한다.
 *
 * @param id 모델이 매긴 호출 id. 결과를 같은 id의 {@code tool} 메시지로 돌려줄 때 쓴다
 * @param arguments 인자 이름 → 값. JSON이 아니었으면 어댑터가 빈 맵이 아니라 검증 실패로 넘긴다
 */
public record ToolCall(String id, String name, Map<String, String> arguments) {}
