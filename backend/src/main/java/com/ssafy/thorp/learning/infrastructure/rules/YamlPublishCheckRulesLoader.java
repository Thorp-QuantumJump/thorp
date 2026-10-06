package com.ssafy.thorp.learning.infrastructure.rules;

import com.ssafy.thorp.learning.application.port.PublishCheckRulesPort;
import com.ssafy.thorp.learning.domain.model.PublishCheckRules;
import com.ssafy.thorp.learning.domain.model.PublishCheckRules.ForbiddenGroup;
import com.ssafy.thorp.learning.domain.model.PublishCheckRulesV2;
import com.ssafy.thorp.learning.domain.model.PublishCheckRulesV2.QuizRules;
import com.ssafy.thorp.learning.domain.model.PublishCheckRulesV2.ReadingRules;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * 클래스패스의 {@code learning/publish-checks-v1.yml}과 {@code learning/publish-checks-v2.yml}을 기동 시 한 번
 * 읽는다. 003 {@code FileParameterRegistry}와 같은 방식이다.
 *
 * <p><b>두 파일 가운데 하나라도 없거나 형식이 틀리면 기동하지 않는다.</b> 빈 목록으로 대신하면 금지 표현 검사가 아무것도 걸지 않은 채 게시를 통과시킨다(원칙
 * VI). v1은 V1 이전 판 고치기가, v2는 V2 게시가 쓴다(research R19).
 *
 * <p>목록을 바꾸면 파일 이름과 {@code version}을 함께 올린다. 옛 파일은 지우지 않는다. 판이 그 버전을 기록하기 때문이다.
 */
@Component
public class YamlPublishCheckRulesLoader implements PublishCheckRulesPort {

    static final String V1_RESOURCE_PATH = "learning/publish-checks-v1.yml";
    static final String V2_RESOURCE_PATH = "learning/publish-checks-v2.yml";

    private final PublishCheckRules v1;
    private final PublishCheckRulesV2 v2;

    public YamlPublishCheckRulesLoader() {
        this(new ClassPathResource(V1_RESOURCE_PATH), new ClassPathResource(V2_RESOURCE_PATH));
    }

    YamlPublishCheckRulesLoader(Resource v1Resource, Resource v2Resource) {
        this.v1 = parseV1(v1Resource);
        this.v2 = parseV2(v2Resource);
    }

    @Override
    public PublishCheckRules v1() {
        return v1;
    }

    @Override
    public PublishCheckRulesV2 v2() {
        return v2;
    }

    /** v1 파일을 읽는다. 금지 표현·숫자 패턴·허용 예외만 있다. */
    static PublishCheckRules parseV1(Resource resource) {
        Map<String, Object> document = read(resource);
        try {
            return common(document);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw invalid(resource, invalid);
        }
    }

    /** v2 파일을 읽는다. v1의 키에 예시 표지·예시 요구·읽기 시간·확인 문제 규칙이 더해진다. */
    static PublishCheckRulesV2 parseV2(Resource resource) {
        Map<String, Object> document = read(resource);
        try {
            Map<String, Object> reading = mapping(document, "reading");
            List<Integer> minutes = range(reading, "minutesRange");
            Map<String, Object> quiz = mapping(document, "quiz");
            List<Integer> questions = range(quiz, "questionsRange");
            List<Integer> choices = range(quiz, "choicesRange");
            return new PublishCheckRulesV2(
                    common(document),
                    text(document, "exampleMarker"),
                    bool(document, "exampleRequired"),
                    new ReadingRules(
                            integer(reading, "charsPerMinute"),
                            minutes.get(0),
                            minutes.get(1),
                            integer(reading, "minDwellDivisor"),
                            integer(reading, "minDwellFloorSeconds")),
                    new QuizRules(
                            questions.get(0), questions.get(1), choices.get(0), choices.get(1)));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw invalid(resource, invalid);
        }
    }

    private static PublishCheckRules common(Map<String, Object> document) {
        return new PublishCheckRules(
                text(document, "version"),
                forbiddenGroups(mapping(document, "forbiddenExpressions")),
                patterns(strings(document, "resultNumberPatterns")),
                Set.copyOf(new LinkedHashSet<>(strings(document, "allowedExceptions"))));
    }

    private static IllegalStateException invalid(Resource resource, RuntimeException cause) {
        return new IllegalStateException(
                "invalid publish check rules: " + resource + ": " + cause.getMessage(), cause);
    }

    private static List<ForbiddenGroup> forbiddenGroups(Map<String, Object> groups) {
        List<ForbiddenGroup> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : groups.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?>)) {
                throw new IllegalStateException(
                        "forbidden group must be a mapping: " + entry.getKey());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> group = (Map<String, Object>) entry.getValue();
            result.add(
                    new ForbiddenGroup(
                            entry.getKey(), text(group, "message"), strings(group, "expressions")));
        }
        return result;
    }

    private static List<Pattern> patterns(List<String> sources) {
        List<Pattern> patterns = new ArrayList<>();
        for (String source : sources) {
            try {
                patterns.add(Pattern.compile(source));
            } catch (PatternSyntaxException invalid) {
                throw new IllegalStateException(
                        "invalid result number pattern: " + source, invalid);
            }
        }
        return patterns;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            Object loaded = new Yaml().load(stream);
            if (!(loaded instanceof Map<?, ?>)) {
                throw new IllegalStateException(
                        "publish check rules file is not a mapping: " + resource);
            }
            return (Map<String, Object>) loaded;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read publish check rules: " + resource, e);
        }
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalStateException(
                    "publish check rules key must be a non-blank string: " + key);
        }
        return string;
    }

    private static boolean bool(Map<String, Object> source, String key) {
        if (!(source.get(key) instanceof Boolean value)) {
            throw new IllegalStateException("publish check rules key must be a boolean: " + key);
        }
        return value;
    }

    private static int integer(Map<String, Object> source, String key) {
        if (!(source.get(key) instanceof Integer value)) {
            throw new IllegalStateException("publish check rules key must be an integer: " + key);
        }
        return value;
    }

    /** {@code [하한, 상한]} 두 정수. */
    private static List<Integer> range(Map<String, Object> source, String key) {
        if (!(source.get(key) instanceof List<?> list)
                || list.size() != 2
                || !(list.get(0) instanceof Integer)
                || !(list.get(1) instanceof Integer)) {
            throw new IllegalStateException(
                    "publish check rules key must be a [min, max] integer pair: " + key);
        }
        return List.of((Integer) list.get(0), (Integer) list.get(1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("publish check rules key must be a mapping: " + key);
        }
        return (Map<String, Object>) value;
    }

    private static List<String> strings(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("publish check rules key must be a list: " + key);
        }
        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String string)) {
                throw new IllegalStateException(
                        "publish check rules list must hold strings: " + key + ": " + item);
            }
            strings.add(string);
        }
        return strings;
    }
}
