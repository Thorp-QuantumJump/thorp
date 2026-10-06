package com.ssafy.thorp.assistant.application.result;

import java.util.List;

/**
 * 도구 인자 하나(spec 017 data-model §5).
 *
 * @param allowedValues {@code ENUM}이면 허용 값의 닫힌 집합(비어 있지 않다). {@code DATE}면 빈 목록
 */
public record ToolArgument(String name, ToolArgumentType type, List<String> allowedValues) {

    public ToolArgument {
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        if (type == ToolArgumentType.ENUM && allowedValues.isEmpty()) {
            throw new IllegalArgumentException("열거 인자는 허용 값이 하나 이상 있어야 한다: " + name);
        }
    }

    public static ToolArgument date(String name) {
        return new ToolArgument(name, ToolArgumentType.DATE, List.of());
    }

    public static ToolArgument oneOf(String name, List<String> allowedValues) {
        return new ToolArgument(name, ToolArgumentType.ENUM, allowedValues);
    }
}
