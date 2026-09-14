package com.ssafy.thorp.paramsearch.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.application.result.SelectionResult;
import com.ssafy.thorp.paramsearch.application.service.CandidateSelector;
import com.ssafy.thorp.paramsearch.application.service.selection.DrawdownFilter;
import com.ssafy.thorp.paramsearch.application.service.selection.StabilityFilter;
import com.ssafy.thorp.paramsearch.domain.vo.StabilityScore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기준 전략이 없을 때의 선정(T070·T074a, FR-013d·FR-025·SC-010).
 *
 * <p>기준이 빠지는 길이 둘이고 결과는 같다 — <b>손실 제약을 걸지 않고 그 사실을 표시한다.</b> 조용히 전부 통과시키면 화면은 필터가 걸린 줄 알고, 전부 떨어뜨리면
 * 기준이 없다는 이유로 결과가 비어 버린다.
 */
class BaselineAbsenceTest {

    /** 선정 코드가 사는 곳. 전략 ID 리터럴이 없어야 한다(FR-025). */
    private static final List<Path> SELECTION_SOURCES =
            List.of(
                    Path.of(
                            "src/main/java/com/ssafy/thorp/paramsearch/application/service/CandidateSelector.java"),
                    Path.of(
                            "src/main/java/com/ssafy/thorp/paramsearch/application/service/selection/DrawdownFilter.java"),
                    Path.of(
                            "src/main/java/com/ssafy/thorp/paramsearch/application/service/selection/StabilityFilter.java"),
                    Path.of(
                            "src/main/java/com/ssafy/thorp/paramsearch/application/service/NeighborStabilityCalculator.java"));

    private final CandidateSelector selector =
            new CandidateSelector(new DrawdownFilter(), new StabilityFilter());

    @Test
    @DisplayName("등록표가 기준 전략 역할을 지정하지 않으면 손실 제약을 걸지 않는다")
    void withoutBaselineRoleTheFilterIsSkipped() {
        // 손실 폭이 아무리 커도 떨어지지 않는다 — 비교할 기준 자체가 없다.
        SelectionResult result =
                selector.select(
                        SelectionFixtures.ruleWithoutBaseline(),
                        List.of(SelectionFixtures.completed(1L)),
                        Map.of(1L, SelectionFixtures.performance("0.1000", "-0.9900")),
                        Map.of(1L, StabilityScore.uncalculable()),
                        null);

        assertThat(result.drawdownFilterApplied()).isFalse();
        assertThat(result.displayedCandidateNos()).containsExactly(1);
        assertThat(result.selectionReasonByCandidateId().get(1L).get("passedFilters"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .as("걸지 않은 필터를 통과했다고 기록하면 근거가 거짓이 된다")
                .doesNotContain("DRAWDOWN");
    }

    @Test
    @DisplayName("기준 실행이 실패해 비교할 값이 없어도 같다")
    void failedBaselineRunSkipsTheFilterToo() {
        SelectionResult result =
                selector.select(
                        SelectionFixtures.ruleWithBaseline(),
                        List.of(SelectionFixtures.completed(1L)),
                        Map.of(1L, SelectionFixtures.performance("0.1000", "-0.9900")),
                        Map.of(1L, StabilityScore.uncalculable()),
                        null);

        assertThat(result.drawdownFilterApplied()).isFalse();
        assertThat(result.displayedCandidateNos()).containsExactly(1);
    }

    @Test
    @DisplayName("기준이 있으면 필터를 걸었다고 표시한다")
    void appliedWhenBaselineExists() {
        SelectionResult result =
                selector.select(
                        SelectionFixtures.ruleWithBaseline(),
                        List.of(SelectionFixtures.completed(1L)),
                        Map.of(1L, SelectionFixtures.performance("0.1000", "-0.1000")),
                        Map.of(1L, StabilityScore.uncalculable()),
                        new BigDecimal("-0.2000"));

        assertThat(result.drawdownFilterApplied()).isTrue();
    }

    @Test
    @DisplayName("선정 코드에 등록표 전략 ID가 한 번도 나타나지 않는다")
    void selectionCodeNamesNoStrategy() throws IOException {
        List<String> registryStrategyIds = registryStrategyIds();
        assertThat(registryStrategyIds).as("등록표에서 전략 ID를 읽지 못하면 이 검증이 무의미하다").isNotEmpty();

        for (Path source : SELECTION_SOURCES) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            assertThat(registryStrategyIds)
                    .as("%s 가 전략을 알면 전략이 늘 때마다 선정 코드를 고쳐야 한다(FR-025)", source.getFileName())
                    .noneMatch(code::contains);
        }
    }

    /** 임시 등록표 파일에서 전략 ID만 긁는다. YAML 파서를 끌어오지 않는 이유는 이 검증이 값 하나만 쓰기 때문이다. */
    private static List<String> registryStrategyIds() throws IOException {
        Path registry = Path.of("src/main/resources/registry/parameter-registry-provisional.yml");
        try (Stream<String> lines = Files.lines(registry, StandardCharsets.UTF_8)) {
            return lines.map(String::strip)
                    .filter(line -> line.startsWith("- id:"))
                    .map(line -> line.substring("- id:".length()).strip())
                    .toList();
        }
    }
}
