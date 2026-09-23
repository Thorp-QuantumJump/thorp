package com.ssafy.thorp.sector.application.result;

import java.time.LocalDate;
import java.util.List;

/**
 * 공통 평가 가능 기간 산출 결과. 계약 「출력」 절의 여섯 가지다(contracts/evaluation-window.md).
 *
 * <p>워커 {@code worker.evaluation_window.EvaluationWindow}와 같은 여섯 가지를 담는다. 필드 이름은 계약 JSON의
 * camelCase와 같다.
 *
 * @param commonStart 공통 평가 시작일. <b>자료 부족 구간이 이 날을 덮어도 뒤로 밀지 않는다</b> — 범위는 그대로 두고 {@code
 *     excludedRanges}로 알린다(원칙 VI)
 * @param commonEnd 공통 평가 종료일. {@code min(dataDeadline, 각 후보의 상장폐지일)}이다
 * @param warmupTradingDays 적용된 워밍업. 요청된 전략 중 가장 긴 값이다(FR-007)
 * @param bindingCandidate {@code commonStart}를 결정한 후보. 003이 "이 섹터를 빼면 기간이 늘어납니다"를 안내하는 근거다
 * @param excludedRanges 구간 안의 자료 부족 구간. 사유는 {@code data_gap.reason} 값이다
 * @param evaluable {@code commonStart > commonEnd}이면 false. <b>오류가 아니라 정상 결과다</b> — 계약은 HTTP 200을
 *     요구하고 화면은 "이 조합은 분석할 수 없습니다" 한 문장을 보인다
 */
public record EvaluationWindowResult(
        LocalDate commonStart,
        LocalDate commonEnd,
        int warmupTradingDays,
        String bindingCandidate,
        List<ExcludedRange> excludedRanges,
        boolean evaluable) {

    public EvaluationWindowResult {
        excludedRanges = List.copyOf(excludedRanges);
    }

    /**
     * {@code [commonStart, commonEnd]} 안의 자료 부족 구간.
     *
     * <p><b>검증용 잠금 구간은 여기에 들어오지 않는다</b>(FR-008). 이 목록은 자료가 없는 구간만 담으며, 평가 구간을 탐색·잠금으로 나누는 일은 spec
     * 007이 맡는다.
     */
    public record ExcludedRange(LocalDate start, LocalDate end, String reason) {}
}
