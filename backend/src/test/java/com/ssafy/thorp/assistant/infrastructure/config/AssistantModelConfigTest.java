package com.ssafy.thorp.assistant.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.assistant.application.port.AssistantModelPort;
import com.ssafy.thorp.assistant.application.service.AssistantCapabilities;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/** 키·주소·모델명이 다 있을 때만 어댑터가 뜨고, 비어도 기동은 성공한다(T023, FR-016). */
class AssistantModelConfigTest {
    private static final String[] CONFIGURED = {
        "thorp.assistant.enabled-scopes=RESULT",
        "thorp.assistant.model.api-key=test-key",
        "thorp.assistant.model.base-url=http://127.0.0.1:9/",
        "thorp.assistant.model.model=gpt-5-mini"
    };

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                    .withUserConfiguration(
                            AssistantConfig.class,
                            AssistantModelConfig.class,
                            AssistantCapabilities.class);

    @Test
    @DisplayName("셋 다 있으면 어댑터 빈이 뜨고 capabilities 가 켜진다")
    void configuredEnablesAdapter() {
        runner.withPropertyValues(CONFIGURED)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(AssistantModelPort.class);
                            assertThat(context.getBean(AssistantCapabilities.class).enabled())
                                    .isTrue();
                        });
    }

    @ParameterizedTest(name = "{0} 이 비면")
    @ValueSource(
            strings = {
                "thorp.assistant.model.api-key",
                "thorp.assistant.model.base-url",
                "thorp.assistant.model.model"
            })
    @DisplayName("하나라도 비면 어댑터 빈이 없고 capabilities 는 꺼지며 컨텍스트는 뜬다")
    void missingValueDisablesWithoutFailing(String blankKey) {
        runner.withPropertyValues(CONFIGURED)
                .withPropertyValues(blankKey + "=")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .doesNotHaveBean(AssistantModelPort.class);
                            assertThat(context.getBean(AssistantCapabilities.class).enabled())
                                    .isFalse();
                            assertThat(context.getBean(AssistantCapabilities.class).scopes())
                                    .isEmpty();
                        });
    }

    @Test
    @DisplayName("아무 설정이 없어도(기본값) 컨텍스트는 뜨고 기능은 꺼져 있다")
    void defaultsAreDisabled() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(AssistantModelPort.class);
                    assertThat(context.getBean(AssistantCapabilities.class).enabled()).isFalse();
                });
    }

    @Test
    @DisplayName("prod 설정은 넷 모두 빈 기본값(${VAR:})이라 환경 변수가 없어도 기동한다")
    @SuppressWarnings("unchecked")
    void prodYamlUsesEmptyDefaults() throws IOException {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource("application-prod.yml").getInputStream()) {
            root = new Yaml().load(in);
        }
        Map<String, Object> assistant =
                (Map<String, Object>) ((Map<String, Object>) root.get("thorp")).get("assistant");
        Map<String, Object> model = (Map<String, Object>) assistant.get("model");

        assertThat(assistant.get("enabled-scopes")).isEqualTo("${THORP_ASSISTANT_ENABLED_SCOPES:}");
        assertThat(model.get("api-key")).isEqualTo("${THORP_ASSISTANT_API_KEY:}");
        assertThat(model.get("base-url")).isEqualTo("${THORP_ASSISTANT_BASE_URL:}");
        assertThat(model.get("model")).isEqualTo("${THORP_ASSISTANT_MODEL:}");
    }
}
