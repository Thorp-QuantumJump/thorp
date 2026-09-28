package com.ssafy.thorp.assistant.infrastructure.config;

import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.assistant.application.result.PageKind;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 설정 파일 → 응용 계층 설정값. {@code BacktestLimitConfig}와 같은 방식이다. */
@Configuration
@EnableConfigurationProperties({AssistantProperties.class, AssistantModelProperties.class})
public class AssistantConfig {

    @Bean
    public AssistantSettings assistantSettings(AssistantProperties properties) {
        Set<PageKind> scopes = EnumSet.noneOf(PageKind.class);
        for (String scope : properties.getEnabledScopes()) {
            if (!scope.isBlank()) {
                scopes.add(PageKind.valueOf(scope.strip()));
            }
        }
        AssistantProperties.Limits limits = properties.getLimits();
        AssistantProperties.Timeout timeout = properties.getTimeout();
        AssistantProperties.Answer answer = properties.getAnswer();
        return new AssistantSettings(
                Set.copyOf(scopes),
                limits.getPerIpPerMinute(),
                limits.getPerTokenPerMinute(),
                limits.getPerDay(),
                limits.getQuestionMaxLength(),
                timeout.getTotal(),
                timeout.getFirstCall(),
                timeout.getNextCall(),
                limits.getMaxRounds(),
                limits.getMaxFacts(),
                limits.getMaxFactsPerRound(),
                answer.getMaxLength(),
                answer.getMaxParts());
    }
}
