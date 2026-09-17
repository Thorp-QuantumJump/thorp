package com.ssafy.thorp.regime.infrastructure.macro;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 임시 대역이 <b>한 디렉터리에 모여 있다</b> (계약 §4.1·§4.3, tasks.md T085).
 *
 * <p>008이 완성되면 {@code infrastructure/macro/fixture/}를 통째로 지우는 것으로 폐기가 끝나야 한다. 운영 코드가 그 안의 클래스를
 * <b>이름으로</b> 참조하면 지우는 순간 함께 깨지고, 그때는 폐기가 아니라 리팩터링이 된다.
 *
 * <p>연결은 {@code @ConditionalOnProperty} 하나뿐이다 — 설정을 {@code macro}로 바꾸면 그 빈은 아예 만들어지지 않는다.
 */
@DisplayName("임시 대역 격리")
class FixtureIsolationTest {

    private static final Path REGIME_ROOT = Path.of("src/main/java/com/ssafy/thorp/regime");
    private static final Path FIXTURE_ROOT = REGIME_ROOT.resolve("infrastructure/macro/fixture");

    private static final String FIXTURE_PACKAGE = "regime.infrastructure.macro.fixture";

    @Test
    @DisplayName("운영 코드가 픽스처 클래스를 이름으로 참조하지 않는다")
    void productionCodeDoesNotNameFixture() {
        List<String> forbidden = fixtureTypeNames();
        List<String> offenders = new ArrayList<>();
        for (Path file : productionSources()) {
            String code = read(file);
            if (code.contains(FIXTURE_PACKAGE)) {
                offenders.add(file + ": " + FIXTURE_PACKAGE);
            }
            for (String type : forbidden) {
                if (code.contains(type)) {
                    offenders.add(file + ": " + type);
                }
            }
        }
        assertThat(offenders)
                .as("008 이 오면 macro/fixture/ 를 지우는 것으로 폐기가 끝나야 한다 (계약 §4.3)")
                .isEmpty();
    }

    @Test
    @DisplayName("지워도 남아야 하는 것은 막지 않는다")
    void keepsWhatMustSurviveDeletion() {
        // FIXTURE- 접두사(자료 버전)와 source=fixture(설정 값)는 픽스처 **클래스**가 아니다.
        // 접두사는 data_version LIKE 'FIXTURE-%' 로 행을 폐기하는 조건이고(data-model §6.1),
        // 설정 값은 그 빈을 만들지 않게 하는 스위치라 디렉터리를 지운 뒤에도 그대로 남아야 한다.
        assertThat(fixtureTypeNames()).doesNotContain("FIXTURE-", "fixture");
    }

    @Test
    @DisplayName("픽스처 디렉터리가 실제로 있고 검사가 비어 있지 않다")
    void scansRealFiles() {
        // 경로가 어긋나면 위 시험이 0개를 훑고 공짜로 통과한다.
        assertThat(Files.isDirectory(FIXTURE_ROOT)).isTrue();
        assertThat(productionSources()).hasSizeGreaterThan(20);
    }

    /**
     * 픽스처 디렉터리 <b>밖의</b> 010 소스.
     *
     * <p>손으로 적은 목록을 쓰지 않는다 — 새 파일을 더한 사람이 목록도 같이 고칠 것이라고 기대하면, 고치지 않은 그 파일만 검사 밖으로 나간다.
     */
    private static List<Path> productionSources() {
        try (Stream<Path> paths = Files.walk(REGIME_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(FIXTURE_ROOT))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("regime 소스를 훑지 못했다: " + REGIME_ROOT, e);
        }
    }

    /** 픽스처 디렉터리가 담은 타입 이름. <b>파일 목록에서 읽는다</b> — 손으로 적으면 새 클래스가 검사 밖으로 나간다. */
    private static List<String> fixtureTypeNames() {
        try (Stream<Path> paths = Files.walk(FIXTURE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString().replace(".java", ""))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("픽스처 디렉터리를 훑지 못했다: " + FIXTURE_ROOT, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("소스를 읽지 못했다: " + file, e);
        }
    }
}
