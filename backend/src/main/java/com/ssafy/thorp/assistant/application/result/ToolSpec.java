package com.ssafy.thorp.assistant.application.result;

import java.util.List;

/**
 * 모델에 보이는 도구 정의. 인자는 날짜이거나 요청 시점의 닫힌 열거값뿐이다(015 계약 §2.2, 017 research R2) — 자유 문자열·식별자 인자가 없어 프롬프트
 * 인젝션으로 다른 결과나 초안을 읽을 경로가 없다.
 *
 * @param arguments 인자 정의. 모델이 적은 인자 이름 집합이 이것과 정확히 같아야 실행한다
 */
public record ToolSpec(String name, String description, List<ToolArgument> arguments) {

    public ToolSpec {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    /** 날짜 인자만 받는 도구(015 결과 도구). */
    public static ToolSpec dates(String name, String description, List<String> dateArguments) {
        return new ToolSpec(
                name, description, dateArguments.stream().map(ToolArgument::date).toList());
    }

    public List<String> argumentNames() {
        return arguments.stream().map(ToolArgument::name).toList();
    }
}
