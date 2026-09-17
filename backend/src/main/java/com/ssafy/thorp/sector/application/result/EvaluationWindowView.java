package com.ssafy.thorp.sector.application.result;

import com.ssafy.thorp.sector.application.result.EvaluationWindowResult.ExcludedRange;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /sectors/evaluation-window}의 유스케이스 출력 (contracts/sector-api.md).
 *
 * <p>{@link EvaluationWindowResult}(산식의 결과)에 <b>자료 버전</b>과 <b>화면이 스스로 재판정할 재료</b>를 더한 것이다.
 *
 * <p><b>왜 재료를 함께 내리는가</b>: 003 전략 설정 화면은 사용자가 관측 기간 같은 값을 바꿀 때마다 워밍업이 달라지고 그 자리에서 "기간이 줄어듭니다/분석할 수
 * 없습니다"를 다시 판정한다. 그런데 그 화면의 완료 기준이 "값을 만지는 동안 서버로 요청이 나가지 않는다"여서, 값이 바뀔 때마다 이 엔드포인트를 다시 부르는 방식은 쓸 수
 * 없다. 워밍업을 뺀 시작일은 거래일 달력이 있어야 구해지므로, 한 번의 응답에 달력 조각을 실어 보내고 화면이 {@code commonStart(N) =
 * tradingDaysFromBase.get(N)}으로 계산한다(003 T098, 2026-09-17 합의).
 *
 * @param commonStart 워밍업을 반영한 공통 평가 시작일. <b>워밍업을 채울 만큼 거래일이 없으면 null이다</b> — 없는 날짜를 지어내지 않는다(원칙
 *     VI). 그때 {@code evaluable}은 false다
 * @param commonEnd 공통 평가 종료일. {@code min(dataDeadline, 각 후보의 상장폐지일)}이다
 * @param warmupTradingDays 적용된 워밍업. 전략을 주지 않은 요청에서는 0이다
 * @param bindingCandidate {@code commonStart}를 결정한 후보. 화면이 "이 섹터를 빼면 기간이 늘어납니다"를 안내하는 근거다
 * @param bindingSector {@code bindingCandidate}가 대표하는 섹터 코드. 화면이 종목 코드를 섹터 이름으로 되짚지 않게 하는 자리다
 * @param excludedRanges {@code [commonStart, commonEnd]} 안의 자료 부족 구간. 평가할 수 없으면 빈 목록이다
 * @param evaluable 이 조합을 평가할 수 있는가. <b>false는 오류가 아니라 정상 결과다</b>
 * @param datasetId 이 응답을 만든 자료 버전(FR-013)
 * @param warmupBaseDate 워밍업이 0일 때의 공통 시작일. 기준 후보의 상장일을 거래일 달력에 맞춘 날이다
 * @param tradingDaysFromBase {@code warmupBaseDate}부터 {@code commonEnd}까지의 거래일 오름차순 목록. 요청이 상한을 주지
 *     않았으면 null이고, 주었으면 길이가 <b>상한 + 1</b>을 넘지 않는다
 * @param maxWarmupTradingDays 화면이 계산할 수 있는 워밍업의 상한. {@code tradingDaysFromBase.size() - 1}과 같다. 요청이
 *     상한을 주지 않았으면 null이다
 * @param excludedRangesFromBase {@code [warmupBaseDate, commonEnd]} 안의 자료 부족 구간. 화면이 더 짧은 워밍업으로 다시
 *     계산할 때 필요하다 — {@code excludedRanges}는 워밍업을 반영해 잘려 있어 그 앞의 부족 구간을 알 수 없다. 요청이 상한을 주지 않았으면
 *     null이다
 */
public record EvaluationWindowView(
        LocalDate commonStart,
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

    public EvaluationWindowView {
        excludedRanges = List.copyOf(excludedRanges);
        tradingDaysFromBase = tradingDaysFromBase == null ? null : List.copyOf(tradingDaysFromBase);
        excludedRangesFromBase =
                excludedRangesFromBase == null ? null : List.copyOf(excludedRangesFromBase);
    }
}
