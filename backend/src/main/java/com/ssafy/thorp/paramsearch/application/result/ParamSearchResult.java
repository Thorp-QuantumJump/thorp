package com.ssafy.thorp.paramsearch.application.result;

import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCounts;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import com.ssafy.thorp.paramsearch.domain.model.ValidationStatus;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 묶음 상태 조회 결과(contracts/param-search-api.md §3). 폴링 대상이며 <b>실패해도 200</b>이다 — 상태는 {@code status}로
 * 판단한다({@code GET /backtests/{id}}와 같은 규약).
 *
 * @param partial 부분 결과인가. 화면이 "여기까지 계산했다"를 밝히는 근거다(FR-009)
 * @param selection 현재까지 완료된 후보의 선정 결과. {@code COMPLETED}·{@code PARTIAL}에서 확정된다
 */
public record ParamSearchResult(
        String searchId,
        ParamSearchStatus status,
        StopReason stopReason,
        Counts counts,
        boolean partial,
        String partialNote,
        Config config,
        Baseline baseline,
        String validationLabel,
        String validationNote,
        Map<String, Object> selection,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * 손실 제약의 기준이 된 실행(FR-013d, 계약 §3).
     *
     * <p>등록표가 기준 전략 역할을 지정하지 않았으면 전체가 null이다. 실행이 아직 안 끝났거나 실패했으면 {@code netReturn}·{@code
     * maxDrawdown}이 null이고, 그때 선정은 손실 제약을 걸지 않은 채 {@code drawdownFilterApplied=false}로 알린다.
     */
    public record Baseline(
            String strategyId,
            String strategyName,
            String backtestId,
            String status,
            BigDecimal netReturn,
            BigDecimal maxDrawdown) {}

    public record Counts(
            long expected,
            long valid,
            long completed,
            long failed,
            long excluded,
            long pending,
            long skipped) {

        static Counts from(SearchCounts counts) {
            return new Counts(
                    counts.expected(),
                    counts.valid(),
                    counts.completed(),
                    counts.failed(),
                    counts.excluded(),
                    counts.pending(),
                    counts.skipped());
        }
    }

    /** 접수 시점에 고정된 값들. 이후 바뀌지 않는다(FR-013c) — 그래서 재현 기록이 된다(FR-020). */
    public record Config(
            List<String> strategyIds,
            List<String> instrumentIds,
            String registryVersion,
            String searchMethod,
            Integer randomSeed,
            Map<String, Object> space,
            Map<String, Object> selectionRule,
            Map<String, Object> limits,
            Map<String, Object> referenceVersions,
            boolean dataVersionChanged) {}

    public static ParamSearchResult from(ParamSearch search) {
        return from(search, null);
    }

    /**
     * 기준 실행 정보를 함께 담는다.
     *
     * @param baseline 기준 실행. 지정이 없거나 아직 읽지 않았으면 null
     */
    public static ParamSearchResult from(ParamSearch search, Baseline baseline) {
        SearchCounts counts = search.getCounts();
        // 실행 중에도, 끝난 뒤에도 같은 문장을 쓴다. 세 응답이 각자 문구를 들면 한 곳만 고쳐진다(FR-015).
        ValidationStatus validation = ValidationStatus.of(search.getEvaluationSplit());
        boolean partial = search.getStatus() == ParamSearchStatus.PARTIAL;
        return new ParamSearchResult(
                search.getSearchId() == null ? null : search.getSearchId().value(),
                search.getStatus(),
                search.getStopReason(),
                Counts.from(counts),
                partial,
                partial ? partialNote(counts) : null,
                new Config(
                        search.getStrategyIds(),
                        search.getInstrumentIds(),
                        search.getRegistryVersion(),
                        search.getSearchMethod().name(),
                        search.getRandomSeed(),
                        toSpaceMap(search.getSearchSpace()),
                        search.getSelectionRule().toMap(),
                        search.getLimits(),
                        search.getReferenceVersions(),
                        search.isDataVersionChanged()),
                baseline,
                validation.label().name(),
                validation.note(),
                search.getSelection(),
                search.getStartedAt(),
                search.getFinishedAt());
    }

    /** 부분 결과 안내 문장. 숫자를 문장에 담는 이유는 "몇 개 중 몇 개까지"가 사용자의 다음 판단(다시 돌릴지, 이대로 볼지)을 가르기 때문이다(FR-009). */
    private static String partialNote(SearchCounts counts) {
        return "유효 후보 %d개 중 %d개까지 계산했습니다. %d개는 실행되지 않았습니다"
                .formatted(counts.valid(), counts.completed() + counts.failed(), counts.skipped());
    }

    /** 저장된 탐색 공간을 그대로 내보낸다 — 화면이 "무엇을 탐색했는지"를 읽는 유일한 출처다(FR-020). */
    private static Map<String, Object> toSpaceMap(SearchSpace space) {
        Map<String, Object> map = new LinkedHashMap<>();
        space.strategies()
                .forEach(
                        (strategyId, strategy) -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("strategyName", strategy.strategyName());
                            entry.put(
                                    "axes",
                                    strategy.axes().stream()
                                            .map(
                                                    axis -> {
                                                        Map<String, Object> axisMap =
                                                                new LinkedHashMap<>();
                                                        axisMap.put("name", axis.name());
                                                        axisMap.put("label", axis.label());
                                                        axisMap.put("unit", axis.unit());
                                                        axisMap.put("values", axis.values());
                                                        return axisMap;
                                                    })
                                            .toList());
                            map.put(strategyId, entry);
                        });
        return map;
    }
}
