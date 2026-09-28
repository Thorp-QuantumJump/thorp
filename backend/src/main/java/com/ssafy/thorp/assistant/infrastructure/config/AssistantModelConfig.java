package com.ssafy.thorp.assistant.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.assistant.application.port.AssistantModelPort;
import com.ssafy.thorp.assistant.infrastructure.client.AssistantHttpClient;
import com.ssafy.thorp.assistant.infrastructure.client.OpenAiChatCompletionsClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 모델 어댑터는 키·주소·모델명이 <b>모두</b> 있을 때만 뜬다. 하나라도 비면 이 설정 전체가 빠지고 {@code
 * AssistantCapabilities.enabled()}가 false 다 — 기동은 정상이다(FR-016, 계약 §4).
 */
@Configuration
@Conditional(AssistantModelConfig.ModelConfigured.class)
public class AssistantModelConfig {

    @Bean
    public AssistantHttpClient assistantHttpClient(
            AssistantModelProperties properties, ObjectMapper mapper) {
        return new AssistantHttpClient(properties, mapper);
    }

    @Bean
    public AssistantModelPort openAiChatCompletionsClient(
            AssistantHttpClient http, AssistantModelProperties properties, ObjectMapper mapper) {
        return new OpenAiChatCompletionsClient(http, properties, mapper);
    }

    /** SpEL 대신 코드로 판정한다 — 키 문자열을 표현식에 끼워 넣지 않기 위해서다. */
    static class ModelConfigured implements Condition {
        static final String[] KEYS = {
            "thorp.assistant.model.api-key",
            "thorp.assistant.model.base-url",
            "thorp.assistant.model.model"
        };

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            for (String key : KEYS) {
                String value = environment.getProperty(key, "");
                if (value.isBlank()) {
                    return false;
                }
            }
            return true;
        }
    }
}
