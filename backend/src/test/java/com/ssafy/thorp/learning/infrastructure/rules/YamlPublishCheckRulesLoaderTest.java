package com.ssafy.thorp.learning.infrastructure.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.learning.domain.model.PublishCheckRules;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

class YamlPublishCheckRulesLoaderTest {

    private static final String VALID =
            """
            version: learning-publish-checks-test
            forbiddenExpressions:
              recommendation:
                message: 추천으로 읽히는 표현이 있습니다.
                expressions: [추천 비중]
            resultNumberPatterns:
              - '\\d+%'
            allowedExceptions: ['100%']
            """;

    private static ByteArrayResource yaml(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("실제 규칙 파일을 읽고 버전과 표현·패턴 수가 파일과 같다")
    @SuppressWarnings("unchecked")
    void readsRealFile() throws IOException {
        PublishCheckRules rules = new YamlPublishCheckRulesLoader().current();

        Map<String, Object> file;
        try (InputStream stream =
                new ClassPathResource(YamlPublishCheckRulesLoader.RESOURCE_PATH).getInputStream()) {
            file = new Yaml().load(stream);
        }
        Map<String, Map<String, Object>> groups =
                (Map<String, Map<String, Object>>) file.get("forbiddenExpressions");
        int expressionCount =
                groups.values().stream()
                        .mapToInt(group -> ((List<?>) group.get("expressions")).size())
                        .sum();

        assertThat(rules.version()).isEqualTo("learning-publish-checks-v1");
        assertThat(rules.forbiddenGroups()).hasSize(groups.size());
        assertThat(
                        rules.forbiddenGroups().stream()
                                .mapToInt(group -> group.expressions().size())
                                .sum())
                .isEqualTo(expressionCount);
        assertThat(rules.resultNumberPatterns())
                .hasSize(((List<?>) file.get("resultNumberPatterns")).size());
        assertThat(rules.allowedExceptions()).containsExactly("100%");
    }

    @Test
    @DisplayName("형식이 맞는 합성 파일은 읽힌다")
    void readsSyntheticFile() {
        PublishCheckRules rules = new YamlPublishCheckRulesLoader(yaml(VALID)).current();

        assertThat(rules.version()).isEqualTo("learning-publish-checks-test");
        assertThat(rules.forbiddenGroups().get(0).expressions()).containsExactly("추천 비중");
    }

    @Test
    @DisplayName("version이 없으면 기동에 실패한다")
    void missingVersionFails() {
        String content = VALID.replace("version: learning-publish-checks-test\n", "");

        assertThatThrownBy(() -> new YamlPublishCheckRulesLoader(yaml(content)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version");
    }

    @Test
    @DisplayName("패턴 문법이 틀리면 기동에 실패한다")
    void invalidPatternFails() {
        String content = VALID.replace("'\\d+%'", "'[0-9+'");

        assertThatThrownBy(() -> new YamlPublishCheckRulesLoader(yaml(content)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pattern");
    }

    @Test
    @DisplayName("금지 표현 목록이 비었거나 공백이 겹친 표현이 있으면 기동에 실패한다")
    void invalidExpressionsFail() {
        assertThatThrownBy(
                        () -> new YamlPublishCheckRulesLoader(yaml(VALID.replace("[추천 비중]", "[]"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                new YamlPublishCheckRulesLoader(
                                        yaml(VALID.replace("[추천 비중]", "['추천  비중']"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("파일이 없으면 기동에 실패한다")
    void missingFileFails() {
        assertThatThrownBy(
                        () ->
                                new YamlPublishCheckRulesLoader(
                                        new ClassPathResource("learning/no-such-file.yml")))
                .isInstanceOf(IllegalStateException.class);
    }
}
