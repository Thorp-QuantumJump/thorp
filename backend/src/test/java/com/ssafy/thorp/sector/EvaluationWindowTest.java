package com.ssafy.thorp.sector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.sector.application.result.EvaluationWindowResult;
import com.ssafy.thorp.sector.application.service.EvaluationWindowService;
import com.ssafy.thorp.sector.application.service.EvaluationWindowService.Candidate;
import com.ssafy.thorp.sector.application.service.EvaluationWindowService.DataGap;
import com.ssafy.thorp.sector.application.service.EvaluationWindowService.EvaluationWindowException;
import com.ssafy.thorp.sector.application.service.EvaluationWindowService.Strategy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 공통 평가 기간 산식을 공유 픽스처로 검증한다 (contracts/evaluation-window.md, SC-007).
 *
 * <p>워커 {@code worker/tests/test_evaluation_window.py}와 <b>같은 파일</b>을 읽는다. 테스트 리소스로 복사하지 않는다 — 복사하면
 * 어긋나고, 한쪽만 고쳐도 빌드가 통과해 버린다(contracts/README.md 「계약 변경 순서」).
 *
 * <p>픽스처를 찾지 못하면 건너뛰지 않고 <b>실패한다</b>. 건너뛰게 두면 파일명이 바뀌는 순간 두 구현을 묶는 장치가 조용히 사라진다.
 *
 * <p><b>순수 단위 테스트다.</b> {@code @SpringBootTest}도 DB도 쓰지 않는다 — 산식 검증에 웹 계층도 저장소도 필요 없고, 새 스프링 컨텍스트를
 * 띄우면 공용 컨테이너를 나눠 쓰는 다른 통합 테스트가 영향을 받는다. gradle 테스트의 작업 디렉터리는 {@code backend/}이므로 경로는 리포 루트 기준으로 한
 * 단계 올라간다({@code MessageFixtures}와 같은 방식).
 */
class EvaluationWindowTest {

    private static final Path FIXTURE =
            Path.of(
                    "..",
                    "specs",
                    "001-etf-market-data",
                    "contracts",
                    "fixtures",
                    "evaluation-window-cases.json");

    /** 계약 「경계 동작」이 요구하는 일곱 가지. 케이스를 지우면 여기서 걸린다. */
    private static final Set<String> REQUIRED_BOUNDARY_CASES =
            Set.of(
                    "common-start-after-common-end",
                    "single-candidate",
                    "empty-candidates",
                    "zero-warmup",
                    "delisted-candidate-pulls-end",
                    "gap-covers-common-start",
                    "longest-warmup-wins");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EvaluationWindowService service = new EvaluationWindowService();

    /** 케이스 이름을 붙여 넘긴다. 실패한 케이스를 이름으로 알 수 있어야 한다. */
    static Stream<Arguments> cases() {
        return loadCases().map(c -> Arguments.of(c.get("name").asText(), c));
    }

    /** 파일이 없으면 여기서 예외가 나고 JUnit은 오류로 끝난다. 조용히 건너뛰지 않는다. */
    private static Stream<JsonNode> loadCases() {
        if (!Files.isRegularFile(FIXTURE)) {
            throw new IllegalStateException(
                    "공유 픽스처가 없다: " + FIXTURE.toAbsolutePath() + ". 워커와 백엔드를 묶는 유일한 파일이므로 건너뛰지 않는다");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(FIXTURE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        JsonNode cases = root.get("cases");
        if (cases == null || cases.isEmpty()) {
            throw new IllegalStateException("공유 픽스처에 케이스가 없다: " + FIXTURE.toAbsolutePath());
        }
        return StreamSupport.stream(cases.spliterator(), false);
    }

    @Test
    @DisplayName("계약 「경계 동작」 일곱 가지가 모두 픽스처에 있다")
    void fixtureCoversEveryContractBoundary() {
        Set<String> names =
                loadCases()
                        .map(c -> c.get("name").asText())
                        .collect(Collectors.toUnmodifiableSet());
        assertThat(names).containsAll(REQUIRED_BOUNDARY_CASES);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void fixtureCase(String name, JsonNode testCase) {
        boolean hasExpected = testCase.has("expected");
        boolean hasError = testCase.has("error");
        // 둘 다 없으면 케이스가 아무것도 검증하지 않는다. 그런 케이스가 조용히 지나가면 안 된다.
        assertThat(hasExpected ^ hasError).as("%s: expected 와 error 중 정확히 하나여야 한다", name).isTrue();

        JsonNode input = testCase.get("input");
        if (hasError) {
            assertThatThrownBy(() -> call(input))
                    .isInstanceOf(EvaluationWindowException.class)
                    .extracting(e -> ((EvaluationWindowException) e).code())
                    .isEqualTo(testCase.get("error").asText());
            return;
        }

        EvaluationWindowResult window = call(input);
        JsonNode expected = testCase.get("expected");
        assertThat(window.commonStart()).isEqualTo(date(expected.get("commonStart")));
        assertThat(window.commonEnd()).isEqualTo(date(expected.get("commonEnd")));
        assertThat(window.warmupTradingDays()).isEqualTo(expected.get("warmupTradingDays").asInt());
        assertThat(window.bindingCandidate()).isEqualTo(expected.get("bindingCandidate").asText());
        assertThat(window.evaluable()).isEqualTo(expected.get("evaluable").asBoolean());
        assertThat(window.excludedRanges())
                .isEqualTo(excludedRanges(expected.get("excludedRanges")));
    }

    @Test
    @DisplayName("자료 부족 구간이 시작일을 덮어도 시작일을 밀지 않는다")
    void dataGapDoesNotPushCommonStart() {
        // 픽스처 gap-covers-common-start가 같은 규칙을 묶고 있지만, 이 단언은 규칙 자체를 문장으로 남긴다 —
        // 부족 구간의 끝을 뒤로 밀어도 commonStart는 꿈쩍하지 않아야 한다(원칙 VI).
        List<LocalDate> calendar =
                List.of(2, 3, 4, 5, 8, 9, 10, 11, 12).stream()
                        .map(day -> LocalDate.of(2024, 1, day))
                        .toList();
        Candidate candidate =
                new Candidate(
                        "091160.XKRX",
                        LocalDate.of(2024, 1, 2),
                        null,
                        List.of(
                                new DataGap(
                                        LocalDate.of(2024, 1, 2),
                                        LocalDate.of(2024, 1, 11),
                                        "AMBIGUOUS_PRICE")));

        EvaluationWindowResult window =
                service.compute(
                        List.of(candidate),
                        List.of(new Strategy("SECTOR_MOMENTUM", 2)),
                        LocalDate.of(2024, 1, 12),
                        calendar);

        assertThat(window.commonStart()).isEqualTo(LocalDate.of(2024, 1, 4));
        assertThat(window.evaluable()).isTrue();
        assertThat(window.excludedRanges())
                .containsExactly(
                        new EvaluationWindowResult.ExcludedRange(
                                LocalDate.of(2024, 1, 4),
                                LocalDate.of(2024, 1, 11),
                                "AMBIGUOUS_PRICE"));
    }

    private EvaluationWindowResult call(JsonNode input) {
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode raw : input.get("candidates")) {
            JsonNode delisted = raw.get("delistedOn");
            List<DataGap> gaps = new ArrayList<>();
            JsonNode rawGaps = raw.get("dataGaps");
            if (rawGaps != null) {
                for (JsonNode gap : rawGaps) {
                    gaps.add(
                            new DataGap(
                                    date(gap.get("start")),
                                    date(gap.get("end")),
                                    gap.get("reason").asText()));
                }
            }
            candidates.add(
                    new Candidate(
                            raw.get("instrumentId").asText(),
                            date(raw.get("listedOn")),
                            delisted == null || delisted.isNull() ? null : date(delisted),
                            gaps));
        }

        List<Strategy> strategies = new ArrayList<>();
        for (JsonNode raw : input.get("strategies")) {
            strategies.add(
                    new Strategy(
                            raw.get("strategyId").asText(), raw.get("warmupTradingDays").asInt()));
        }

        List<LocalDate> calendar = new ArrayList<>();
        input.get("tradingCalendar").forEach(day -> calendar.add(date(day)));

        return service.compute(candidates, strategies, date(input.get("dataDeadline")), calendar);
    }

    private static List<EvaluationWindowResult.ExcludedRange> excludedRanges(JsonNode raw) {
        List<EvaluationWindowResult.ExcludedRange> ranges = new ArrayList<>();
        raw.forEach(
                node ->
                        ranges.add(
                                new EvaluationWindowResult.ExcludedRange(
                                        date(node.get("start")),
                                        date(node.get("end")),
                                        node.get("reason").asText())));
        return ranges;
    }

    /**
     * {@code LocalDate.parse}는 엄격한 ISO 날짜만 받는다. 워커는 같은 값을 {@code date.fromisoformat}으로 읽는데 그쪽이 더
     * 너그러우므로, 픽스처에는 두 쪽이 모두 받는 {@code YYYY-MM-DD}만 적는다.
     */
    private static LocalDate date(JsonNode node) {
        return LocalDate.parse(node.asText());
    }
}
