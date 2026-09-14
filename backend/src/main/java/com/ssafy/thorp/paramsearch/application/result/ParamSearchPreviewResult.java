package com.ssafy.thorp.paramsearch.application.result;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 실행 전 확인 결과(contracts/param-search-api.md §1). 확인 화면의 네 항목을 채운다 — 대상 전략·탐색 범위 요약·예상 작업량·적용
 * 한도(FR-003).
 *
 * <p><b>여기 담긴 전략 정보는 전부 등록표와 실행 지원 목록에서 옮긴 값이다.</b> 서버도 화면도 전략 이름·라벨·단위·기본값·후보값·허용 범위를 자체적으로 갖지
 * 않는다(FR-025·FR-027).
 *
 * @param registryVersion 조회한 등록표 버전. 실행 요청이 이 값을 되돌려 보낸다(FR-028)
 * @param strategies 대상 전략과 실행 지원 여부
 * @param spaceSummary 전략별 축 요약. 순서가 그리드 좌표의 축 순서다(FR-029)
 * @param workload 예상 작업량
 * @param limits 적용 한도와 임시값 표시
 * @param commonPeriod 모든 후보가 공유할 평가 구간
 * @param validationLabel 실행 전에도 알리는 검증 라벨(R5)
 * @param validationNote 라벨의 이유를 사람이 읽는 한 문장
 */
public record ParamSearchPreviewResult(
        String registryVersion,
        List<StrategySummary> strategies,
        List<StrategySpaceSummary> spaceSummary,
        Workload workload,
        Limits limits,
        CommonPeriod commonPeriod,
        String validationLabel,
        String validationNote) {

    /**
     * @param executable 004가 실행을 지원하는가(FR-026)
     */
    public record StrategySummary(String strategyId, String name, boolean executable) {}

    /**
     * @param combinations 이 전략이 만드는 조합 수 — 축별 후보 개수의 곱이다
     */
    public record StrategySpaceSummary(
            String strategyId, String strategyName, List<AxisSummary> axes, long combinations) {}

    /**
     * @param values 실제 탐색할 후보값. 인덱스가 그리드 좌표다
     * @param defaultValue 등록된 기본 시작값. <b>추천값이나 최적값이 아니다</b>(원칙 III)
     */
    public record AxisSummary(
            String axis,
            String label,
            String unit,
            List<Object> values,
            Object defaultValue,
            AllowedRangeSummary allowedRange) {}

    public record AllowedRangeSummary(Object min, Object max, Object step, List<Object> values) {}

    /**
     * @param exclusionReasons 사유별 제외 건수. 합이 {@code excludedCombinations}와 같다
     */
    public record Workload(
            long totalCombinations,
            long validCombinations,
            long excludedCombinations,
            List<ExclusionReason> exclusionReasons) {}

    public record ExclusionReason(String code, String message, long count) {}

    /**
     * @param provisional 임시값인 한도 항목 이름. 화면이 임시값 표시를 붙인다(FR-005a)
     * @param exceeded 유효 후보 수가 상한을 넘었는가. true면 실행은 거부된다(FR-005b)
     */
    public record Limits(
            int maxCandidates,
            long timeLimitSeconds,
            int dispatchWindow,
            int consecutiveFailureLimit,
            List<String> provisional,
            boolean exceeded) {

        public Map<String, Object> toSnapshot() {
            return Map.of(
                    "maxCandidates", maxCandidates,
                    "timeLimitSeconds", timeLimitSeconds,
                    "dispatchWindow", dispatchWindow,
                    "consecutiveFailureLimit", consecutiveFailureLimit,
                    "provisional", provisional);
        }
    }

    /**
     * @param shortenedBy 긴 관측 기간을 요구하는 후보 때문에 구간이 줄었다면 그 축 이름(Edge Case)
     */
    public record CommonPeriod(LocalDate start, LocalDate end, String shortenedBy) {}
}
