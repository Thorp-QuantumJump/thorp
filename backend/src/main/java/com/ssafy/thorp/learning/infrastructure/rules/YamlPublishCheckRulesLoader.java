package com.ssafy.thorp.learning.infrastructure.rules;

import com.ssafy.thorp.learning.application.port.PublishCheckRulesPort;
import com.ssafy.thorp.learning.domain.model.PublishCheckRules;
import com.ssafy.thorp.learning.domain.model.PublishCheckRules.ForbiddenGroup;
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
 * 클래스패스의 {@code learning/publish-checks-v1.yml}을 기동 시 한 번 읽는다. 003 {@code FileParameterRegistry}와
 * 같은 방식이다.
 *
 * <p><b>파일이 없거나 형식이 틀리면 기동하지 않는다.</b> 빈 목록으로 대신하면 금지 표현 검사가 아무것도 걸지 않은 채 게시를 통과시킨다(원칙 VI).
 *
 * <p>목록을 바꾸면 파일 이름과 {@code version}을 함께 올린다. 옛 파일은 지우지 않는다. 판이 그 버전을 기록하기 때문이다.
 */
@Component
public class YamlPublishCheckRulesLoader implements PublishCheckRulesPort {

    static final String RESOURCE_PATH = "learning/publish-checks-v1.yml";

    private final PublishCheckRules rules;

    public YamlPublishCheckRulesLoader() {
        this(new ClassPathResource(RESOURCE_PATH));
    }

    YamlPublishCheckRulesLoader(Resource resource) {
        this.rules = load(resource);
    }

    @Override
    public PublishCheckRules current() {
        return rules;
    }

    private static PublishCheckRules load(Resource resource) {
        Map<String, Object> document = read(resource);
        try {
            return new PublishCheckRules(
                    text(document, "version"),
                    forbiddenGroups(mapping(document, "forbiddenExpressions")),
                    patterns(strings(document, "resultNumberPatterns")),
                    Set.copyOf(new LinkedHashSet<>(strings(document, "allowedExceptions"))));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalStateException(
                    "invalid publish check rules: " + resource + ": " + invalid.getMessage(),
                    invalid);
        }
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
