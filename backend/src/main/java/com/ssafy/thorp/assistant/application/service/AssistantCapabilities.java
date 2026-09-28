package com.ssafy.thorp.assistant.application.service;

import com.ssafy.thorp.assistant.application.port.AssistantModelPort;
import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.assistant.application.result.PageKind;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 기능 플래그(FR-016). 켜진 화면 종류가 있고 모델 어댑터 빈이 떠 있어야 켜진다.
 *
 * <p>어댑터 빈은 키·주소·모델명이 모두 있을 때만 뜬다(묶음 3). 그래서 "키가 없으면 비활성이고 기동은 정상"이 설정 하나로 성립한다.
 */
@Component
public class AssistantCapabilities {
    private final AssistantSettings settings;
    private final ObjectProvider<AssistantModelPort> model;

    public AssistantCapabilities(
            AssistantSettings settings, ObjectProvider<AssistantModelPort> model) {
        this.settings = settings;
        this.model = model;
    }

    public boolean enabled() {
        return !settings.enabledScopes().isEmpty() && model.getIfAvailable() != null;
    }

    public boolean supports(PageKind kind) {
        return enabled() && settings.enabledScopes().contains(kind);
    }

    /** 켜진 종류를 선언 순서로. 꺼져 있으면 빈 목록이다(계약 §1). */
    public List<PageKind> scopes() {
        if (!enabled()) {
            return List.of();
        }
        return Arrays.stream(PageKind.values()).filter(settings.enabledScopes()::contains).toList();
    }
}
