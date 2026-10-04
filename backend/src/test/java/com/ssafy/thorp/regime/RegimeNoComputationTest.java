package com.ssafy.thorp.regime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code regime} 패키지에 <b>계산 코드도 지표 시계열 조회도 없다</b> (spec SC-002a, 계약 §0.1, tasks.md T108·T109).
 *
 * <p>010의 계산은 전부 {@code data/} 배치에 있고 Backend는 적재된 행을 옮길 뿐이다. 그 사실을 응답 시간으로 증명할 수는 없다 — 캐시가 붙거나 자료가
 * 작으면 계산해도 빠르다. <b>구조가 증거다.</b>
 *
 * <p>검사 대상은 손으로 적은 목록이 아니라 <b>디렉터리를 훑어</b> 모은다. 목록을 적어 두면 새 파일을 더한 사람이 목록도 같이 고칠 것이라고 기대하게 되고, 고치지
 * 않은 그 파일만 검사 밖으로 나간다.
 */
@DisplayName("regime 패키지에 계산과 시계열 조회가 없다")
class RegimeNoComputationTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/ssafy/thorp/regime");

    /**
     * 금지하는 산술.
     *
     * <p><b>{@code +}는 뺀다.</b> 오류 문구를 잇는 문자열 결합이 곳곳에 있어 그것까지 막으면 검사가 문구 스타일 규칙이 되어 버린다. 그래도 이 검사는
     * 약해지지 않는다 — 척도는 {@code (x − m) ÷ s}이고 거리는 평균이라 {@code -}와 {@code /} 없이는 만들 수 없다. 더하기만으로 도달할 수
     * 있는 계산이 없다.
     */
    private static final Pattern ARITHMETIC = Pattern.compile("[-*/%]");

    private static final List<String> FORBIDDEN_CALLS =
            List.of(
                    "Math.",
                    ".add(",
                    ".subtract(",
                    ".multiply(",
                    ".divide(",
                    ".negate(",
                    ".pow(",
                    ".sqrt(",
                    ".setScale(",
                    ".doubleValue(",
                    ".floatValue(");

    /**
     * 지표 시계열을 가리키는 이름. 여기 닿는 순간 Backend가 008의 값을 직접 읽는 것이다(계약 §0.1).
     *
     * <p>Java 쪽 통로는 008 조회 저장소의 관측값 메서드와 그 행 타입이고, SQL 쪽 통로는 관측값 표 {@code macro_observation}이다. 표
     * 이름은 SQL 문자열 안에만 나타나므로 이 검사는 문자열을 남긴 코드에서 찾는다.
     */
    private static final List<String> TIME_SERIES_NAMES =
            List.of(
                    "findObservations",
                    "findLatestValidMonths",
                    "ObservationRow",
                    "LatestMonthRow",
                    "macro_observation",
                    "monthlySeries",
                    "rawSeries");

    @Test
    @DisplayName("산술 연산이 0건이다")
    void hasNoArithmetic() {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String code = strip(read(file));
            Matcher matcher = ARITHMETIC.matcher(code);
            if (matcher.find()) {
                offenders.add(file.getFileName() + ": " + snippet(code, matcher.start()));
            }
        }
        assertThat(offenders).as("regime 패키지가 계산을 하고 있다. 척도·거리는 data/ 배치의 몫이다 (SC-002a)").isEmpty();
    }

    @Test
    @DisplayName("Math·BigDecimal 산술 호출이 0건이다")
    void hasNoArithmeticCalls() {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String code = strip(read(file));
            for (String call : FORBIDDEN_CALLS) {
                if (code.contains(call)) {
                    offenders.add(file.getFileName() + ": " + call);
                }
            }
        }
        assertThat(offenders).as("저장된 값을 고쳐 내보내면 화면의 숫자와 배치가 기록한 값이 갈린다 (research R4)").isEmpty();
    }

    @Test
    @DisplayName("지표 시계열을 읽는 이름이 0건이다")
    void doesNotReadIndicatorSeries() {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            // 주석은 걷어 낸다. 포트의 javadoc 이 "여기에 monthlySeries() 를 두지 않는다"라고
            // 설명하는 자리까지 위반으로 잡으면, 그 설명을 지우는 방향으로 고치게 된다.
            // 문자열은 남긴다. 걷어 내면 SQL로 관측값 표를 읽어도 보이지 않는다.
            String code = stripComments(read(file));
            for (String name : TIME_SERIES_NAMES) {
                if (code.contains(name)) {
                    offenders.add(file.getFileName() + ": " + name);
                }
            }
        }
        assertThat(offenders).as("008에서 읽는 것은 포트의 등록표(이름·단위·분야 순서)뿐이다 (계약 §0.1)").isEmpty();
    }

    @Test
    @DisplayName("검사할 파일이 실제로 있다")
    void scansRealFiles() {
        // 경로가 어긋나 0개를 훑으면 위 셋이 전부 공짜로 통과한다.
        assertThat(sources()).hasSizeGreaterThan(20);
    }

    private static List<Path> sources() {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("regime 소스를 훑지 못했다: " + SOURCE_ROOT, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("소스를 읽지 못했다: " + file, e);
        }
    }

    /**
     * 주석·문자열·람다 화살표를 걷어 낸다.
     *
     * <p>걷어 내지 않으면 javadoc의 {@code ÷}, 오류 문구의 {@code /}, {@code item -> ...}의 화살표가 전부 산술로 잡혀 검사가 곧
     * 꺼진다. 꺼진 검사는 없는 검사보다 나쁘다 — 통과한다는 사실이 근거처럼 읽힌다.
     */
    private static String strip(String code) {
        String withoutText = stripComments(code).replaceAll("(?s)\"\"\".*?\"\"\"", " ");
        String withoutStrings = withoutText.replaceAll("\"(\\\\.|[^\"\\\\])*\"", " ");
        String withoutChars = withoutStrings.replaceAll("'(\\\\.|[^'\\\\])'", " ");
        return withoutChars.replace("->", " ");
    }

    /** 주석만 걷어 낸다. 문자열은 남으므로 SQL에 적힌 표 이름도 보인다. */
    private static String stripComments(String code) {
        String withoutBlockComments = code.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlockComments.replaceAll("//[^\\n]*", " ");
    }

    private static String snippet(String code, int at) {
        int from = Math.max(0, at - 40);
        int to = Math.min(code.length(), at + 40);
        return code.substring(from, to).replaceAll("\\s+", " ");
    }
}
