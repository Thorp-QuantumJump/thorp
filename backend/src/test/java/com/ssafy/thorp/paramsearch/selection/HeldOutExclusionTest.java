package com.ssafy.thorp.paramsearch.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.application.port.CandidatePerformance;
import com.ssafy.thorp.paramsearch.application.result.SelectionResult;
import com.ssafy.thorp.paramsearch.application.service.CandidateSelector;
import com.ssafy.thorp.paramsearch.application.service.NeighborStabilityCalculator;
import com.ssafy.thorp.paramsearch.application.service.selection.DrawdownFilter;
import com.ssafy.thorp.paramsearch.application.service.selection.StabilityFilter;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.vo.StabilityScore;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 잠금 구간 성과는 선정에 들어가지 않는다(T109, SC-005, FR-014·FR-015).
 *
 * <p><b>이것이 지켜지지 않으면 검증이 검증이 아니게 된다.</b> 잠금 구간은 "탐색에 쓰지 않은 구간"이라서 값이 있다 — 그 값으로 후보를 고르면 그 구간도 탐색에 쓴
 * 셈이 되고, 남는 검증 구간이 없다. 그래서 선정이 보는 성과는 <b>탐색 구간의 것 하나</b>여야 한다.
 *
 * <p>지금은 평가 분할(PD-12)이 미등록이라 잠금 구간 자체가 없고 {@code periodPerformance}는 비어 있다. 그래서 이 테스트는 "지금 새는가"가
 * 아니라 <b>분할이 생겼을 때 샐 수 있는 통로가 있는가</b>를 본다 — 선정 경로가 후보의 구간별 성과를 아예 읽지 않는지, 그리고 구간별 성과를 채워 두어도 결과가
 * 달라지지 않는지.
 *
 * <p>구간별 성과가 채워지는 날 이 테스트가 깨지면 그것이 신호다. 그때는 라벨별로 성과를 갈라 선정에 넣을 것을 명시적으로 고르는 변경이 필요하고, 조용히 통과하면 안
 * 된다.
 */
class HeldOutExclusionTest {

    /** 선정 경로의 코드. 여기에 구간별 성과를 읽는 곳이 있으면 잠금 구간이 샌다. */
    private static final List<Path> SELECTION_SOURCES =
            List.of(
                    Path.of(
                            "src/main/java/com/ssafy/thorp/paramsearch/application/service/CandidateSelector.java"),
                    Path.of(
                            "src/main/java/com/ssafy/thorp/paramsearch/application/service/NeighborStabilityCalculator.java"),
                    Path.of(
                            "src/main/java/com/ssafy/thorp/paramsearch/application/service/selection/DrawdownFilter.java"),
                    Path.of(
                            "src/main/java/com/ssafy/thorp/paramsearch/application/service/selection/StabilityFilter.java"));

    private final CandidateSelector selector =
            new CandidateSelector(new DrawdownFilter(), new StabilityFilter());
    private final NeighborStabilityCalculator stabilityCalculator =
            new NeighborStabilityCalculator();

    @Test
    @DisplayName("선정 경로 어디에서도 구간별 성과를 읽지 않는다")
    void selectionNeverReadsPeriodPerformance() throws IOException {
        for (Path source : SELECTION_SOURCES) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            assertThat(code)
                    .as("%s 가 구간별 성과를 읽으면 잠금 구간이 선정에 새어 든다(SC-005)", source.getFileName())
                    .doesNotContain("getPeriodPerformance")
                    .doesNotContain("periodPerformance");
        }
    }

    @Test
    @DisplayName("선정이 받는 성과 타입에는 구간 구분이 없다 — 섞일 자리가 없다")
    void theSelectionInputCarriesOneSetOfNumbers() {
        List<String> accessors =
                Arrays.stream(CandidatePerformance.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList();

        assertThat(accessors)
                .as("선정은 탐색 구간 성과 하나만 본다. 구간별 항목이 생기면 어느 것을 쓸지 명시해야 한다")
                .containsExactly("netReturn", "maxDrawdown", "tradingCost");
        assertThat(
                        Arrays.stream(CandidateSelector.class.getMethods())
                                .map(Method::getName)
                                .filter(name -> name.toLowerCase().contains("period"))
                                .toList())
                .isEmpty();
    }

    @Test
    @DisplayName("후보에 구간별 성과를 채워 두어도 선정 결과가 달라지지 않는다")
    void fillingPeriodPerformanceDoesNotChangeTheOutcome() {
        List<SearchCandidate> plain =
                List.of(
                        SelectionFixtures.completed(1L),
                        SelectionFixtures.completed(2L),
                        SelectionFixtures.completed(3L));
        // 같은 후보에 잠금 구간 성과를 붙인다. 값은 탐색 구간과 정반대로 둬서, 새면 순서가 뒤집히게 한다.
        List<SearchCandidate> withHeldOut =
                List.of(
                        plain.get(0)
                                .withPeriodPerformance(
                                        heldOut("0.0100"), plain.get(0).getValidationLabel()),
                        plain.get(1)
                                .withPeriodPerformance(
                                        heldOut("0.5000"), plain.get(1).getValidationLabel()),
                        plain.get(2)
                                .withPeriodPerformance(
                                        heldOut("0.9000"), plain.get(2).getValidationLabel()));

        SelectionResult before = select(plain);
        SelectionResult after = select(withHeldOut);

        assertThat(after.displayedCandidateNos())
                .as("잠금 구간 값이 선정에 새면 순서가 뒤집힌다")
                .isEqualTo(before.displayedCandidateNos());
        assertThat(after.selectionReasonByCandidateId())
                .isEqualTo(before.selectionReasonByCandidateId());
    }

    /** 탐색 구간 성과만으로 선정한다 — 안정성도 같은 숫자에서 나온다. */
    private SelectionResult select(List<SearchCandidate> candidates) {
        Map<Long, CandidatePerformance> performance = new LinkedHashMap<>();
        Map<Long, BigDecimal> netReturns = new LinkedHashMap<>();
        String[] searchPeriodReturns = {"0.3000", "0.2000", "0.1000"};
        for (int i = 0; i < candidates.size(); i++) {
            long id = candidates.get(i).getId();
            performance.put(id, SelectionFixtures.performance(searchPeriodReturns[i], "-0.1000"));
            netReturns.put(id, new BigDecimal(searchPeriodReturns[i]));
        }
        Map<Long, StabilityScore> stability = stabilityCalculator.calculate(candidates, netReturns);
        return selector.select(
                SelectionFixtures.ruleWithBaseline(),
                candidates,
                performance,
                stability,
                new BigDecimal("-0.2000"));
    }

    private static Map<String, Object> heldOut(String netReturn) {
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("heldOut", Map.of("netReturn", netReturn));
        return period;
    }
}
