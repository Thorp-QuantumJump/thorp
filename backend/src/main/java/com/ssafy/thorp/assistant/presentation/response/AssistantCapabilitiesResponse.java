package com.ssafy.thorp.assistant.presentation.response;

import com.ssafy.thorp.assistant.application.result.PageKind;
import java.util.List;

/** {@code GET /assistant/capabilities}. 꺼져 있으면 {@code scopes}는 빈 배열이고 화면은 버튼을 그리지 않는다(계약 §1). */
public record AssistantCapabilitiesResponse(boolean enabled, List<String> scopes) {

    public static AssistantCapabilitiesResponse of(boolean enabled, List<PageKind> scopes) {
        return new AssistantCapabilitiesResponse(enabled, scopes.stream().map(Enum::name).toList());
    }
}
