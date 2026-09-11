package com.ssafy.thorp.sector.application.service;

import com.ssafy.thorp.sector.application.result.EvaluationWindowResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 공통 평가 가능 기간 산식 (contracts/evaluation-window.md, FR-007·FR-008).
 *
 * <p>워커 {@code worker/src/worker/evaluation_window.py}가 같은 산식을 파이썬으로 구현한다. 두 구현은 같은 픽스처 {@code
 * specs/001-etf-market-data/contracts/fixtures/evaluation-window-cases.json}를 읽어 검증하므로 한쪽만 고치면 다른 쪽
 * 빌드가 깨진다. 계산 위치를 한쪽으로 옮기지 않고 산식을 계약으로 공유하는 것이 결정이다(research.md 결정 3).
 *
 * <p>산식:
 *
 * <pre>
 * warmup = max(strategies[].warmupTradingDays)
 *
 * 각 후보 c에 대해
 *   firstEvaluable(c) = 거래일 달력에서 c.listedOn 이후 warmup 번째 거래일
 *   lastEvaluable(c)  = c.delistedOn ?? dataDeadline
 *
 * commonStart = max over c of firstEvaluable(c)
 * commonEnd   = min(dataDeadline, min over c of lastEvaluable(c))
 *
 * excludedRanges = 모든 후보의 dataGaps를 합집합한 뒤 [commonStart, commonEnd]로 자른 것
 * </pre>
 *
 * <p><b>검증용 잠금 구간은 넣지 않는다</b>(FR-008). 반환값은 자료로 평가할 수 있는 전체 범위이고, 탐색 구간과 잠금 구간으로 나누는 일은 spec 007의
 * 평가 분할이 맡는다. 여기서 미리 나누면 007이 쓸 수 없는 값이 된다.
 *
 * <p><b>자료 부족 구간이 {@code commonStart}를 포함해도 시작일을 밀지 않는다.</b> 범위는 그대로 두고 {@code excludedRanges}로 알린다
 * — 조용히 밀면 화면이 왜 기간이 짧아졌는지 설명할 수 없다(원칙 VI).
 *
 * <p><b>의존성이 없는 순수 산식이다.</b> 저장소도 엔티티도 참조하지 않고 입력을 전부 인자로 받는다. 지금 이 산식을 HTTP로 내보내는 엔드포인트는 없다 — 입력 넷
 * 중 {@code strategies[].warmupTradingDays}는 계약이 "전략 등록표(003 소유)"라고 적었고 003이 아직 없으며, {@code
 * tradingCalendar}·{@code dataDeadline}·{@code dataGaps}·{@code delistedOn}은 백엔드에 읽는 경로가 없다. 값을
 * 지어내는 표면을 만들지 않는다(원칙 VI). 그 재료가 생기면 <b>이 클래스를 고치지 않고</b> 호출부만 붙이면 된다.
 *
 * <p>백테스트 계산이 아니다. 최댓값·최솟값을 고르는 조합 연산이며 시계열을 훑지 않는다(research.md 결정 3).
 *
 * <p>입력 record를 {@code application/command}가 아니라 이 클래스 안에 두었다. 계약 「입력」 절의 네 가지가 이 산식 하나에만 쓰이고, 산식과
 * 그 입력 모양을 한 파일에서 함께 읽는 편이 낫다. 다른 유스케이스가 같은 입력을 쓰게 되면 그때 {@code command}로 옮긴다.
 */
@Service
public class EvaluationWindowService {

    /**
     * {@code representative_etf}·{@code data_gap}의 CHECK 제약, {@code RepresentativeEtf}와 같은 형식이다.
     *
     * <p>{@link String#matches(String)}는 전체 일치라 워커의 {@code re.fullmatch}와 허용 범위가 같다. 파이썬 쪽에서 {@code
     * $}를 쓰면 끝에 붙은 줄바꿈 앞에서도 맞아 한쪽이 받는 값을 다른 쪽이 거부하게 된다.
     */
    private static final Pattern INSTRUMENT_ID_PATTERN = Pattern.compile("[0-9]{6}\\.XKRX");

    /**
     * 공통 평가 가능 기간을 산출한다.
     *
     * @param candidates 후보 대표 ETF. 비어 있으면 오류다 — 후보 없이 기간을 산출하지 않는다
     * @param strategies 요청된 전략. 가장 긴 워밍업이 쓰인다(FR-007). 001은 이 값을 정하지 않고 받는다
     * @param dataDeadline {@code dataset_version.data_deadline}
     * @param tradingCalendar 거래일 달력. 확보한 지수 자료({@code daily_index})의 날짜 집합이다
     * @throws EvaluationWindowException 산식을 적용할 수 없는 입력일 때. <b>평가 불가는 오류가 아니다</b> — {@code
     *     commonStart > commonEnd}는 {@code evaluable = false}인 정상 결과다
     */
    public EvaluationWindowResult compute(
            List<Candidate> candidates,
            List<Strategy> strategies,
            LocalDate dataDeadline,
            List<LocalDate> tradingCalendar) {
        Objects.requireNonNull(dataDeadline, "dataDeadline must not be null");

        int warmup = resolveWarmup(candidates, strategies);
        List<LocalDate> calendar = resolveCalendar(tradingCalendar);
        // 식별자 검사를 따로 한 바퀴 돈다. firstEvaluable 안에서 함께 보면 앞선 후보의 CALENDAR_TOO_SHORT가
        // 뒤에 있는 형식 위반보다 먼저 터져, 픽스처가 못박은 판정 순서와 어긋난다.
        candidates.forEach(candidate -> requireInstrumentId(candidate.instrumentId()));

        // 동점이면 입력 순서가 앞선 후보다. 계약이 정하지 않아 정했고 픽스처 binding-candidate-tie가
        // 두 구현을 묶는다. 순회하며 '더 늦을 때만' 갱신하므로 첫 최댓값이 남는다(워커와 같은 규칙).
        int bindingIndex = 0;
        LocalDate commonStart = null;
        LocalDate commonEnd = dataDeadline;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            LocalDate firstEvaluable = firstEvaluable(candidate, warmup, calendar);
            if (commonStart == null || firstEvaluable.isAfter(commonStart)) {
                commonStart = firstEvaluable;
                bindingIndex = i;
            }
            LocalDate lastEvaluable =
                    candidate.delistedOn() != null ? candidate.delistedOn() : dataDeadline;
            if (lastEvaluable.isBefore(commonEnd)) {
                commonEnd = lastEvaluable;
            }
        }

        boolean evaluable = !commonStart.isAfter(commonEnd);
        // 구간이 뒤집혔으면 자를 대상이 없다.
        List<EvaluationWindowResult.ExcludedRange> excluded =
                evaluable ? mergeGaps(candidates, commonStart, commonEnd) : List.of();

        return new EvaluationWindowResult(
                commonStart,
                commonEnd,
                warmup,
                candidates.get(bindingIndex).instrumentId(),
                excluded,
                evaluable);
    }

    /** 검증 순서는 픽스처의 {@code $validationOrder}와 같다. 워커 구현도 같은 순서다. */
    private static int resolveWarmup(List<Candidate> candidates, List<Strategy> strategies) {
        if (candidates == null || candidates.isEmpty()) {
            throw new EvaluationWindowException("EMPTY_CANDIDATES", "후보 없이 공통 평가 기간을 산출하지 않는다");
        }
        if (strategies == null || strategies.isEmpty()) {
            throw new EvaluationWindowException("EMPTY_STRATEGIES", "전략이 없으면 워밍업 최댓값을 정할 수 없다");
        }
        int warmup = strategies.stream().mapToInt(Strategy::warmupTradingDays).max().orElseThrow();
        if (warmup < 0) {
            throw new EvaluationWindowException(
                    "NEGATIVE_WARMUP", "warmupTradingDays는 0 이상이어야 한다: " + warmup);
        }
        return warmup;
    }

    /** 달력을 정렬·중복 제거한다. 원본 순서를 신뢰하지 않는다 — 조회 순서는 호출부 사정이다. */
    private static List<LocalDate> resolveCalendar(List<LocalDate> tradingCalendar) {
        if (tradingCalendar == null || tradingCalendar.isEmpty()) {
            throw new EvaluationWindowException("EMPTY_CALENDAR", "거래일 달력이 비어 있으면 거래일을 셀 수 없다");
        }
        return List.copyOf(new TreeSet<>(tradingCalendar));
    }

    private static void requireInstrumentId(String instrumentId) {
        if (instrumentId == null || !INSTRUMENT_ID_PATTERN.matcher(instrumentId).matches()) {
            throw new EvaluationWindowException(
                    "INVALID_INSTRUMENT_ID", "종목 식별자 형식이 아니다: " + instrumentId);
        }
    }

    /**
     * 달력에서 {@code listedOn} 이상인 거래일 중 0-기준 {@code warmup}번째.
     *
     * <p>{@code warmup}이 0이면 {@code listedOn}이 그대로 나온다(계약 경계 동작). 달력이 모자라면 마지막 거래일로 떨어뜨리지 않고 실패한다 —
     * 없는 날짜를 지어내지 않는다(원칙 VI).
     */
    private static LocalDate firstEvaluable(
            Candidate candidate, int warmup, List<LocalDate> calendar) {
        int found = Collections.binarySearch(calendar, candidate.listedOn());
        // binarySearch는 없으면 (-(삽입 위치) - 1)을 준다. 삽입 위치가 곧 listedOn 이상인 첫 거래일이다.
        int start = found >= 0 ? found : -found - 1;
        int index = start + warmup;
        if (index >= calendar.size()) {
            throw new EvaluationWindowException(
                    "CALENDAR_TOO_SHORT",
                    candidate.instrumentId() + "의 상장일 이후 거래일이 워밍업 " + warmup + "일에 모자란다");
        }
        return calendar.get(index);
    }

    /**
     * 모든 후보의 부족 구간을 합집합한 뒤 {@code [commonStart, commonEnd]}로 자른다.
     *
     * <p>사유가 같고 겹치는 구간끼리만 합친다. 사유가 다른 구간을 합치면 {@code data_gap.reason}을 잃는다. 정렬은 (start, end,
     * reason) 오름차순이다.
     */
    private static List<EvaluationWindowResult.ExcludedRange> mergeGaps(
            List<Candidate> candidates, LocalDate commonStart, LocalDate commonEnd) {
        Map<String, List<LocalDate[]>> clipped = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            for (DataGap gap : candidate.dataGaps()) {
                LocalDate start = gap.start().isBefore(commonStart) ? commonStart : gap.start();
                LocalDate end = gap.end().isAfter(commonEnd) ? commonEnd : gap.end();
                if (!start.isAfter(end)) {
                    clipped.computeIfAbsent(gap.reason(), key -> new ArrayList<>())
                            .add(new LocalDate[] {start, end});
                }
            }
        }

        List<EvaluationWindowResult.ExcludedRange> merged = new ArrayList<>();
        for (Map.Entry<String, List<LocalDate[]>> entry : clipped.entrySet()) {
            List<LocalDate[]> ranges = entry.getValue();
            ranges.sort(
                    Comparator.<LocalDate[], LocalDate>comparing(r -> r[0])
                            .thenComparing(r -> r[1]));
            LocalDate currentStart = null;
            LocalDate currentEnd = null;
            for (LocalDate[] range : ranges) {
                if (currentEnd != null && !range[0].isAfter(currentEnd)) {
                    currentEnd = currentEnd.isAfter(range[1]) ? currentEnd : range[1];
                    continue;
                }
                if (currentEnd != null) {
                    merged.add(
                            new EvaluationWindowResult.ExcludedRange(
                                    currentStart, currentEnd, entry.getKey()));
                }
                currentStart = range[0];
                currentEnd = range[1];
            }
            if (currentEnd != null) {
                merged.add(
                        new EvaluationWindowResult.ExcludedRange(
                                currentStart, currentEnd, entry.getKey()));
            }
        }

        merged.sort(
                Comparator.comparing(EvaluationWindowResult.ExcludedRange::start)
                        .thenComparing(EvaluationWindowResult.ExcludedRange::end)
                        .thenComparing(EvaluationWindowResult.ExcludedRange::reason));
        return List.copyOf(merged);
    }

    /**
     * 산식을 적용할 수 없는 입력. {@code code}는 픽스처의 {@code error} 값과 같은 문자열이다.
     *
     * <p>평가 불가({@code commonStart > commonEnd})는 여기에 해당하지 않는다. 그것은 오류가 아니라 {@code evaluable =
     * false}인 정상 결과이며 계약이 HTTP 200을 요구한다.
     */
    public static class EvaluationWindowException extends IllegalArgumentException {

        private final String code;

        public EvaluationWindowException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 후보 하나의 자료 부족 구간. {@code reason}은 {@code data_gap.reason} 값이다(FR-006·FR-017). */
    public record DataGap(LocalDate start, LocalDate end, String reason) {}

    /** 후보 대표 ETF 하나. 출처는 {@code representative_etf}와 {@code data_gap}이다. */
    public record Candidate(
            String instrumentId, LocalDate listedOn, LocalDate delistedOn, List<DataGap> dataGaps) {

        public Candidate {
            dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
        }
    }

    /**
     * 요청된 전략 하나. {@code warmupTradingDays}는 관측 기간 + 최근 제외 구간이다(FR-007).
     *
     * <p>001은 이 값을 정하지 않고 받는다. 전략 등록표는 spec 003이 소유한다.
     */
    public record Strategy(String strategyId, int warmupTradingDays) {}
}
