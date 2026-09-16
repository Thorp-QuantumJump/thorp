package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * 007 운영 코드에 전략 지식이 스며들지 않았는지 정적으로 검사한다(T133, FR-025, SC-010).
 *
 * <p>동작 테스트로는 이것을 잡을 수 없다. 전략 ID 분기가 하나 생겨도 그 전략을 쓰는 테스트는 여전히 통과하고, 새 전략을 넣어 보는 테스트를 누군가 지우면 그대로
 * 묻힌다. 그래서 소스를 읽어 <b>전략 이름이 운영 코드에 등장하는지</b>를 본다.
 *
 * <p>검사 대상은 {@code paramsearch} 운영 소스와 {@code strategy} 운영 소스 둘이다. 테스트 fixture와 자원 파일은 뺀다 — 전략 데이터가
 * 있어야 할 곳이 자원 파일이고, fixture는 FR-025의 명시적 예외다.
 *
 * <p><b>{@code strategy}를 함께 보는 이유</b>: 등록표를 읽는 로더가 003 소유로 옮겨 갔다. 검사 대상을 {@code paramsearch} 하나로
 * 두면 "로더는 파일 구조만 읽고 전략 이름을 알아서는 안 된다"는 이 클래스의 본래 대상이 검사에서 조용히 빠진다 — 테스트는 여전히 초록이므로 아무도 알아채지 못한다.
 * 이관으로 007 FR-025의 보장이 약해지지 않아야 한다.
 */
class StrategyIndependenceTest {

    private static final List<Path> SOURCE_ROOTS =
            List.of(
                    Path.of("src/main/java/com/ssafy/thorp/paramsearch"),
                    Path.of("src/main/java/com/ssafy/thorp/strategy"));
    private static final String REGISTRY_RESOURCE = "registry/parameter-registry.yml";

    /** 전략 분기의 흔한 형태. 축 이름 문자열도 같은 이유로 금지하고 싶지만 그것은 등록표 계약의 키라 대상이 아니다. */
    private static final List<Pattern> BRANCHING =
            List.of(
                    Pattern.compile("switch\\s*\\(\\s*\\w*[sS]trategyId"),
                    Pattern.compile("\\w*[sS]trategyId\\s*(\\.equals|==)\\s*\""),
                    Pattern.compile(
                            "\"[A-Z][A-Z0-9_]{3,}\"\\s*\\.equals\\s*\\(\\s*\\w*[sS]trategyId"));

    @Test
    @DisplayName("운영 코드에 등록표의 전략 ID·전략명이 문자열로 나타나지 않는다")
    void noStrategyNamesInProductionSources() {
        Set<String> forbidden = registryStrategyWords();
        assertThat(forbidden).as("등록표에서 전략 이름을 읽지 못하면 이 검사가 무력해진다").isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Path source : productionSources()) {
            String content = read(source);
            for (String word : forbidden) {
                if (content.contains("\"" + word + "\"")) {
                    violations.add(source + " → \"" + word + "\"");
                }
            }
        }

        assertThat(violations).as("전략 데이터는 등록표에만 있어야 한다(FR-025). 값이 필요하면 포트로 조회한다").isEmpty();
    }

    @Test
    @DisplayName("운영 코드에 strategyId 분기가 없다")
    void noStrategyIdBranching() {
        List<String> violations = new ArrayList<>();
        for (Path source : productionSources()) {
            String content = read(source);
            for (Pattern pattern : BRANCHING) {
                Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    violations.add(source + " → " + matcher.group());
                }
            }
        }

        assertThat(violations).as("전략별 분기는 전략이 늘어날 때마다 고쳐야 하는 코드다(FR-025·SC-010)").isEmpty();
    }

    private static List<Path> productionSources() {
        List<Path> sources = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            try (Stream<Path> paths = Files.walk(root)) {
                List<Path> found = paths.filter(path -> path.toString().endsWith(".java")).toList();
                assertThat(found).as("검사할 운영 소스를 찾지 못했다: %s", root).isNotEmpty();
                sources.addAll(found);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return sources;
    }

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 등록표가 소유한 전략 ID와 전략명. 운영 코드에 이 중 하나라도 문자열로 있으면 하드코딩이다. */
    @SuppressWarnings("unchecked")
    private static Set<String> registryStrategyWords() {
        try (InputStream stream = new ClassPathResource(REGISTRY_RESOURCE).getInputStream()) {
            Map<String, Object> document = new Yaml().load(stream);
            Set<String> words = new LinkedHashSet<>();
            for (Map<String, Object> strategy :
                    (List<Map<String, Object>>) document.get("strategies")) {
                words.add(String.valueOf(strategy.get("id")));
                words.add(String.valueOf(strategy.get("name")));
            }
            return words;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
