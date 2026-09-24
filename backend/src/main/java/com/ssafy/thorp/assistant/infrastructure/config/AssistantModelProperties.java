package com.ssafy.thorp.assistant.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 모델 연결({@code thorp.assistant.model.*}, ADR-0008). 셋 중 하나라도 비면 {@link #configured()}가 false 이고 어댑터
 * 빈(묶음 3)이 뜨지 않는다 — 그러면 {@code capabilities.enabled=false}이고 기동은 정상이다.
 *
 * <p>prod 도 빈 기본값({@code ${THORP_ASSISTANT_API_KEY:}})이다. 기본값 없는 {@code ${ENV}}는 미설정 시 기동 실패라 "없으면
 * 비활성"과 양립하지 않는다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "thorp.assistant.model")
public class AssistantModelProperties {
    private String apiKey = "";
    private String baseUrl = "";
    private String model = "";

    public boolean configured() {
        return !apiKey.isBlank() && !baseUrl.isBlank() && !model.isBlank();
    }
}
