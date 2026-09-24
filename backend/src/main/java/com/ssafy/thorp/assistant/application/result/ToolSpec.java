package com.ssafy.thorp.assistant.application.result;

import java.util.List;

/**
 * 모델에 보이는 도구 정의. 인자는 {@code YYYY-MM-DD} 날짜뿐이다(계약 §2.2) — 식별자 인자가 없어 프롬프트 인젝션으로 다른 결과를 읽을 경로가 없다.
 *
 * @param dateArguments 인자 이름. 모델이 적은 인자 집합이 이것과 정확히 같아야 실행한다
 */
public record ToolSpec(String name, String description, List<String> dateArguments) {}
