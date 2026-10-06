package com.ssafy.thorp.learning.infrastructure.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.learning.domain.model.PublishCheckRules;
import com.ssafy.thorp.learning.domain.model.PublishCheckRulesV2;
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

    private static final String VALID_V2 =
            VALID
                    + """
                    exampleMarker: '(예:'
                    exampleRequired: false
                    reading:
                      charsPerMinute: 500
                      minutesRange: [4, 7]
                      minDwellDivisor: 3
                      minDwellFloorSeconds: 30
                    quiz:
                      questionsRange: [2, 3]
                      choicesRange: [2, 5]
                    """;

    private static ByteArrayResource yaml(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> file(String path) throws IOException {
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            return new Yaml().load(stream);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertCommonMatchesFile(PublishCheckRules rules, Map<String, Object> file) {
        Map<String, Map<String, Object>> groups =
                (Map<String, Map<String, Object>>) file.get("forbiddenExpressions");
        int expressionCount =
                groups.values().stream()
                        .mapToInt(group -> ((List<?>) group.get("expressions")).size())
                        .sum();
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
    @DisplayName("실제 v1 규칙 파일을 읽고 버전과 표현·패턴 수가 파일과 같다")
    void readsRealV1File() throws IOException {
        PublishCheckRules rules = new YamlPublishCheckRulesLoader().v1();

        assertThat(rules.version()).isEqualTo("learning-publish-checks-v1");
        assertCommonMatchesFile(rules, file(YamlPublishCheckRulesLoader.V1_RESOURCE_PATH));
    }

    @Test
    @DisplayName("실제 v2 규칙 파일을 읽고 v2 값(예시 표지·예시 선택·읽기 시간·확인 문제)이 파일과 같다")
    void readsRealV2File() throws IOException {
        PublishCheckRulesV2 rules = new YamlPublishCheckRulesLoader().v2();

        assertThat(rules.version()).isEqualTo("learning-publish-checks-v2");
        assertCommonMatchesFile(rules.common(), file(YamlPublishCheckRulesLoader.V2_RESOURCE_PATH));
        assertThat(rules.exampleMarker()).isEqualTo("(예:");
        assertThat(rules.exampleRequired()).isFalse();
        assertThat(rules.reading())
                .isEqualTo(new PublishCheckRulesV2.ReadingRules(500, 4, 7, 3, 30));
        assertThat(rules.quiz()).isEqualTo(new PublishCheckRulesV2.QuizRules(2, 3, 2, 5));
    }

    @Test
    @DisplayName("v1과 v2의 금지 표현·숫자 패턴·허용 예외는 같다 (data-model §8-3)")
    void v1AndV2ShareCommonRules() {
        YamlPublishCheckRulesLoader loader = new YamlPublishCheckRulesLoader();

        assertThat(loader.v2().common().forbiddenGroups()).isEqualTo(loader.v1().forbiddenGroups());
        assertThat(loader.v2().common().resultNumberPatterns())
                .extracting(Object::toString)
                .isEqualTo(
                        loader.v1().resultNumberPatterns().stream().map(Object::toString).toList());
        assertThat(loader.v2().common().allowedExceptions())
                .isEqualTo(loader.v1().allowedExceptions());
    }

    @Test
    @DisplayName("형식이 맞는 합성 파일은 읽힌다")
    void readsSyntheticFile() {
        PublishCheckRules rules = YamlPublishCheckRulesLoader.parseV1(yaml(VALID));

        assertThat(rules.version()).isEqualTo("learning-publish-checks-test");
        assertThat(rules.forbiddenGroups().get(0).expressions()).containsExactly("추천 비중");
        assertThat(YamlPublishCheckRulesLoader.parseV2(yaml(VALID_V2)).reading().charsPerMinute())
                .isEqualTo(500);
    }

    @Test
    @DisplayName("version이 없으면 기동에 실패한다")
    void missingVersionFails() {
        String content = VALID.replace("version: learning-publish-checks-test\n", "");

        assertThatThrownBy(() -> YamlPublishCheckRulesLoader.parseV1(yaml(content)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version");
    }

    @Test
    @DisplayName("패턴 문법이 틀리면 기동에 실패한다")
    void invalidPatternFails() {
        String content = VALID.replace("'\\d+%'", "'[0-9+'");

        assertThatThrownBy(() -> YamlPublishCheckRulesLoader.parseV1(yaml(content)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pattern");
    }

    @Test
    @DisplayName("금지 표현 목록이 비었거나 공백이 겹친 표현이 있으면 기동에 실패한다")
    void invalidExpressionsFail() {
        assertThatThrownBy(
                        () ->
                                YamlPublishCheckRulesLoader.parseV1(
                                        yaml(VALID.replace("[추천 비중]", "[]"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                YamlPublishCheckRulesLoader.parseV1(
                                        yaml(VALID.replace("[추천 비중]", "['추천  비중']"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("v2 파일에 v2 값이 없거나 범위가 틀리면 기동에 실패한다")
    void invalidV2ValuesFail() {
        assertThatThrownBy(() -> YamlPublishCheckRulesLoader.parseV2(yaml(VALID)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                YamlPublishCheckRulesLoader.parseV2(
                                        yaml(VALID_V2.replace("[4, 7]", "[7, 4]"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                YamlPublishCheckRulesLoader.parseV2(
                                        yaml(VALID_V2.replace("exampleRequired: false", ""))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exampleRequired");
        assertThatThrownBy(
                        () ->
                                YamlPublishCheckRulesLoader.parseV2(
                                        yaml(
                                                VALID_V2.replace(
                                                        "charsPerMinute: 500",
                                                        "charsPerMinute: '500'"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("charsPerMinute");
    }

    @Test
    @DisplayName("v1이나 v2 파일이 없으면 기동에 실패한다")
    void missingFileFails() {
        ClassPathResource missing = new ClassPathResource("learning/no-such-file.yml");
        ClassPathResource v1 = new ClassPathResource(YamlPublishCheckRulesLoader.V1_RESOURCE_PATH);
        ClassPathResource v2 = new ClassPathResource(YamlPublishCheckRulesLoader.V2_RESOURCE_PATH);

        assertThatThrownBy(() -> new YamlPublishCheckRulesLoader(missing, v2))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new YamlPublishCheckRulesLoader(v1, missing))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new YamlPublishCheckRulesLoader(v1, yaml(VALID)))
                .isInstanceOf(IllegalStateException.class);
    }
}
