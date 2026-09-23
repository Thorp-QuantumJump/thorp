package com.ssafy.thorp.paramsearch.presentation.response;

import com.ssafy.thorp.paramsearch.application.result.ParamSearchPreviewResult;
import com.ssafy.thorp.strategy.application.port.AllowedRange;
import java.time.LocalDate;
import java.util.List;

/**
 * 실행 전 확인 응답(contracts/param-search-api.md §1). 확인 화면의 네 항목이 여기 다 들어 있다.
 *
 * <p>결과 record를 그대로 내보내지 않는 이유는 규약이다 — 컨트롤러 DTO와 유스케이스 결과를 분리해 두면 계약을 바꿀 때 application 계층이 흔들리지
 * 않는다(backend CLAUDE.md).
 */
public record ParamSearchPreviewResponse(
        String registryVersion,
        List<Strategy> strategies,
        List<StrategySpace> spaceSummary,
        Workload workload,
        Limits limits,
        CommonPeriod commonPeriod,
        String validationLabel,
        String validationNote) {

    public record Strategy(String strategyId, String name, boolean executable) {}

    public record StrategySpace(
            String strategyId, String strategyName, List<Axis> axes, long combinations) {}

    /**
     * @param valueLabels {@code values}와 같은 순서로 놓인 표시 문구. 화면은 값을 요청에 되돌려 보내고 문구를 그린다 — 객체형 후보는 값만으로
     *     그릴 수 없다
     */
    public record Axis(
            String axis,
            String label,
            String unit,
            List<Object> values,
            List<String> valueLabels,
            Object defaultValue,
            AllowedRange allowedRange) {}

    public record AllowedRange(Object min, Object max, Object step, List<Object> values) {}

    public record Workload(
            long totalCombinations,
            long validCombinations,
            long excludedCombinations,
            List<ExclusionReason> exclusionReasons) {}

    public record ExclusionReason(String code, String message, long count) {}

    /** {@code timeLimitSeconds}는 상한을 두지 않으면 null 이다. 키는 그대로 두어 화면이 "제한 없음"을 말할 수 있게 한다. */
    public record Limits(
            int maxCandidates,
            Long timeLimitSeconds,
            int dispatchWindow,
            int consecutiveFailureLimit,
            List<String> provisional,
            boolean exceeded) {}

    public record CommonPeriod(LocalDate start, LocalDate end, String shortenedBy) {}

    public static ParamSearchPreviewResponse from(ParamSearchPreviewResult result) {
        return new ParamSearchPreviewResponse(
                result.registryVersion(),
                result.strategies().stream()
                        .map(
                                strategy ->
                                        new Strategy(
                                                strategy.strategyId(),
                                                strategy.name(),
                                                strategy.executable()))
                        .toList(),
                result.spaceSummary().stream()
                        .map(
                                space ->
                                        new StrategySpace(
                                                space.strategyId(),
                                                space.strategyName(),
                                                space.axes().stream()
                                                        .map(
                                                                axis ->
                                                                        new Axis(
                                                                                axis.axis(),
                                                                                axis.label(),
                                                                                axis.unit(),
                                                                                axis.values(),
                                                                                axis.valueLabels(),
                                                                                axis.defaultValue(),
                                                                                toRange(axis)))
                                                        .toList(),
                                                space.combinations()))
                        .toList(),
                new Workload(
                        result.workload().totalCombinations(),
                        result.workload().validCombinations(),
                        result.workload().excludedCombinations(),
                        result.workload().exclusionReasons().stream()
                                .map(
                                        reason ->
                                                new ExclusionReason(
                                                        reason.code(),
                                                        reason.message(),
                                                        reason.count()))
                                .toList()),
                new Limits(
                        result.limits().maxCandidates(),
                        result.limits().timeLimitSeconds(),
                        result.limits().dispatchWindow(),
                        result.limits().consecutiveFailureLimit(),
                        result.limits().provisional(),
                        result.limits().exceeded()),
                result.commonPeriod() == null
                        ? null
                        : new CommonPeriod(
                                result.commonPeriod().start(),
                                result.commonPeriod().end(),
                                result.commonPeriod().shortenedBy()),
                result.validationLabel(),
                result.validationNote());
    }

    private static AllowedRange toRange(ParamSearchPreviewResult.AxisSummary axis) {
        return axis.allowedRange() == null
                ? null
                : new AllowedRange(
                        axis.allowedRange().min(),
                        axis.allowedRange().max(),
                        axis.allowedRange().step(),
                        axis.allowedRange().values());
    }
}
