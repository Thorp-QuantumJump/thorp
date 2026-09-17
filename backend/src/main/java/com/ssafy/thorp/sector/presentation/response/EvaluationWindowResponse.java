package com.ssafy.thorp.sector.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.sector.application.result.EvaluationWindowResult;
import com.ssafy.thorp.sector.application.result.EvaluationWindowView;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /sectors/evaluation-window}의 응답 (contracts/sector-api.md·evaluation-window.md).
 *
 * <p>앞의 일곱 칸이 계약의 산식 출력이고, 뒤의 네 칸은 화면이 다른 워밍업으로 <b>스스로 다시 판정할 재료</b>다. 재료가 필요한 까닭은 003 화면이 값을 만지는
 * 동안 서버로 요청을 내보내지 않기로 했기 때문이며, 화면은 {@code commonStart(N) = tradingDaysFromBase.get(N)}으로 계산한다.
 *
 * <p><b>{@code NON_NULL}을 이 응답에만 건다.</b> 요청이 워밍업 상한을 주지 않으면 뒤의 네 칸은 계산하지 않으므로 키 자체를 빼서 "요청하지 않아
 * 없음"을 드러낸다. 웹 ObjectMapper에 전역으로 걸지 않는 이유는 backend/CLAUDE.md가 적어 둔 대로 명시적 null을 계약으로 삼는 응답(거래의
 * {@code exitDate})이 함께 깨지기 때문이다.
 *
 * <p>{@code commonStart}는 예외로 <b>null이어도 키가 남는다</b>. 워밍업을 채울 거래일이 모자라 시작일이 없다는 사실은 화면이 읽어야 하는 값이고,
 * 키가 사라지면 "요청하지 않음"과 구분되지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluationWindowResponse(
        @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate commonStart,
        LocalDate commonEnd,
        int warmupTradingDays,
        String bindingCandidate,
        String bindingSector,
        List<ExcludedRange> excludedRanges,
        boolean evaluable,
        String datasetId,
        LocalDate warmupBaseDate,
        List<LocalDate> tradingDaysFromBase,
        Integer maxWarmupTradingDays,
        List<ExcludedRange> excludedRangesFromBase) {

    public static EvaluationWindowResponse from(EvaluationWindowView view) {
        return new EvaluationWindowResponse(
                view.commonStart(),
                view.commonEnd(),
                view.warmupTradingDays(),
                view.bindingCandidate(),
                view.bindingSector(),
                ExcludedRange.from(view.excludedRanges()),
                view.evaluable(),
                view.datasetId(),
                view.warmupBaseDate(),
                view.tradingDaysFromBase(),
                view.maxWarmupTradingDays(),
                view.excludedRangesFromBase() == null
                        ? null
                        : ExcludedRange.from(view.excludedRangesFromBase()));
    }

    /** 자료가 모자라 평가에서 빠지는 구간. {@code reason}은 {@code data_gap.reason} 값이다. */
    public record ExcludedRange(LocalDate start, LocalDate end, String reason) {

        static List<ExcludedRange> from(List<EvaluationWindowResult.ExcludedRange> ranges) {
            return ranges.stream()
                    .map(range -> new ExcludedRange(range.start(), range.end(), range.reason()))
                    .toList();
        }
    }
}
