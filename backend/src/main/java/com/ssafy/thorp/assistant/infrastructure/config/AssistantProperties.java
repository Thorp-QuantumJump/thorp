package com.ssafy.thorp.assistant.infrastructure.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code thorp.assistant.*}(계약 §4). 기본값은 ADR-0008의 초안 수치이고 실측 뒤 조정한다(원칙 IX).
 *
 * <p>{@code enabled-scopes}가 비어 있으면 기능 전체가 꺼진다. 다른 값이 비어도 기동은 정상이다 — "없으면 기능만 꺼진다"가 이 기능의 요구다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "thorp.assistant")
public class AssistantProperties {
    /** 켜진 화면 종류 이름(쉼표 목록). 1차는 {@code RESULT}. */
    private List<String> enabledScopes = new ArrayList<>();

    private final Limits limits = new Limits();
    private final Timeout timeout = new Timeout();
    private final Answer answer = new Answer();

    @Getter
    @Setter
    public static class Limits {
        private int perIpPerMinute = 8;
        private int perTokenPerMinute = 5;
        private int perDay = 1000;
        private int questionMaxLength = 300;
        private int maxRounds = 2;
        private int maxFacts = 60;
        private int maxFactsPerRound = 30;
    }

    @Getter
    @Setter
    public static class Timeout {
        private Duration total = Duration.ofMinutes(2);
        private Duration firstCall = Duration.ofSeconds(60);
        private Duration nextCall = Duration.ofSeconds(30);
    }

    @Getter
    @Setter
    public static class Answer {
        private int maxLength = 600;
        private int maxParts = 5;
    }
}
