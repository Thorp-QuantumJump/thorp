package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 모든 후보가 공유할 평가 구간과 공통 워밍업을 정한다(FR-007, Edge Case).
 *
 * <p><b>후보마다 구간이 다르면 비교가 성립하지 않는다.</b> 관측 기간이 긴 후보는 더 늦게 시작하는데, 그 후보만 짧은 구간으로 평가하면 수익률이 다른 기간의 값이
 * 되어 나란히 놓을 수 없다(원칙 IV). 그래서 <b>묶음 안에서 가장 긴 관측 기간</b>을 모든 후보에 똑같이 적용한다.
 *
 * <p><b>탐색 구간 시작일 = 요청 시작일 당일 또는 그 뒤 첫 거래일.</b> 워밍업만큼 뒤로 늦추지 않는다. 워밍업 자료는 요청 구간보다 <b>앞에서</b> 읽고, 앞이
 * 모자랄 때 집계 시작일을 늦추는 판정은 워커 {@code engine_runner.evaluation_start()}가 단독으로 맡는다(001 {@code
 * contracts/market-dataset.md} 「읽기 규칙」). 004 {@code EffectivePeriodCalculator}와 같은 규칙이며, 두 곳이 갈라져
 * 있던 것을 {@code S15P21E205-205}·T037에서 맞췄다.
 *
 * <p><b>맞춰야 하는 까닭은 같은 요청이 화면에 따라 다른 구간을 내는 것이었다.</b> 단건 접수로 2023-01-02부터 요청하면 그날부터 평가하는데, 같은 조건을 자동
 * 탐색으로 넣으면 상장일 기준으로 늦춰진 날부터 평가했다. 사용자는 그 차이의 까닭을 알 수 없다.
 *
 * <p><b>요청 시작일이 없으면 {@code commonStart}가 곧 서버의 제안값이다.</b> 제시할 다른 값이 없다. 001 {@code
 * contracts/evaluation-window.md}가 정한 "후보 집합을 평가할 수 있는 가장 이른 날"이며, 요청 시작일이 있을 때는 그 값을 요청 구간 안에 평가할
 * 수 있는 날이 하루도 없는지 가리는 데만 쓴다.
 *
 * <p><b>비교는 그대로 성립한다.</b> 후보마다 관측 길이가 달라도 묶음 전체에 공통 워밍업 하나를 똑같이 적용하고 종목 집합도 고정이라, 워커가 늦추더라도 후보 전부가
 * 같은 날부터 시작한다(원칙 IV).
 *
 * <p>공통 워밍업이 004 접수의 {@code warmupTradingDays}로 그대로 나간다. 004는 이 값이 전략 요구값보다 작으면 422로 거부하므로(R-15)
 * 계산이 틀리면 묶음이 시작 단계에서 드러난다.
 *
 * <p><b>관측 기간이 어느 축인지 007이 알지 못한다.</b> 축 이름으로 "lookback"을 찾지 않고, 등록표가 준 <b>수치 축의 최댓값</b>을 거래일 수로 본다
 * — 축의 뜻을 해석하는 순간 그것이 전략 지식이다(FR-025). 값이 실제로 거래일인지는 004가 판정한다.
 */
@Service
public class CommonPeriodResolver {

    private final MarketMetadataPort marketMetadata;
    private final Clock clock;

    public CommonPeriodResolver(MarketMetadataPort marketMetadata, Clock clock) {
        this.marketMetadata = marketMetadata;
        this.clock = clock;
    }

    /**
     * 공통 구간을 정한다.
     *
     * @param instrumentIds 고정한 섹터 후보 집합. 가장 늦게 상장한 종목이 시작일을 정한다
     * @param space 탐색 공간. 워밍업은 여기 담긴 후보값에서 나온다 — 범위를 좁히면 워밍업도 줄어든다
     * @param requestedStart 사용자가 고른 시작일. 있으면 <b>그날 또는 그 뒤 첫 거래일</b>이고 워밍업만큼 늦추지 않는다. 없으면 상장일 기준으로
     *     서버가 제시한다
     * @param requestedEnd 사용자가 고른 끝일. 없으면 지금 자료 버전의 마지막 거래일
     */
    public CommonPeriod resolve(
            List<String> instrumentIds,
            SearchSpace space,
            List<StrategyRegistration> targets,
            LocalDate requestedStart,
            LocalDate requestedEnd) {
        LocalDate listedFrom = latestListingDate(instrumentIds);
        // 끝일을 받지 않으면 **자료 마감일**을 쓴다. 오늘을 쓰면 수집이 거기까지 끝난 날이 아닌 한
        // 요청 구간이 자료 밖으로 나가고, 워커가 후보를 전부 DATA_INSUFFICIENT 로 거절한다
        // ("Requested window ends ... but ... is only complete through ..."). 주말·공휴일이거나
        // 수집이 하루만 밀려도 탐색 전체가 전멸하던 자리다. 004 단일 실행은 001 evaluation-window 의
        // commonEnd 를 실어 보내 이미 자료 안에 머문다 — 007 만 기준이 달랐다.
        //
        // 달력이 비어 있으면 오늘로 물러난다. 그때는 어차피 이어지는 evaluationStart 조회가 비어
        // INSUFFICIENT_HISTORY 로 끝나므로, 여기서 기본값을 두고 판정을 한 곳에 모은다.
        LocalDate end =
                requestedEnd == null
                        ? marketMetadata.lastTradingDay().orElseGet(() -> LocalDate.now(clock))
                        : requestedEnd;

        WarmupRequirement warmup = warmupOf(space, targets);
        // 워밍업 기준점은 상장일 하나다(001 contracts/evaluation-window.md). 요청 시작일은 여기
        // 들어가지 않고 마지막에 max 로만 합쳐진다 — 요청 앞에 워밍업 자료가 있으면 워커가 요청
        // 구간보다 앞에서 읽으므로 요청 구간을 깎을 이유가 없다(contracts/market-dataset.md).
        LocalDate commonStart =
                marketMetadata
                        .evaluationStart(listedFrom, warmup.tradingDays())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INSUFFICIENT_HISTORY,
                                                "공통 평가 구간을 만들 수 있는 과거 자료가 부족합니다: 워밍업 "
                                                        + warmup.tradingDays()
                                                        + "거래일"));
        // 요청 시작일이 없으면 서버가 제시하는 값이 곧 commonStart 다. 있으면 그것을 거래일에
        // 맞추기만 한다 — commonStart 와 max 하지 않는다(클래스 주석). 거래일 스냅을 남기는 것은
        // 주말을 시작일로 삼으면 004 접수가 고르는 날과 값이 갈리기 때문이다.
        LocalDate start =
                requestedStart == null
                        ? commonStart
                        : marketMetadata
                                .firstTradingDayOnOrAfter(requestedStart)
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.INSUFFICIENT_HISTORY,
                                                        "요청 시작일 이후에 거래일이 없습니다: " + requestedStart));
        // 늦추지 않게 됐어도 거절해야 하는 경계는 남는다. 구간 안에 이 후보 집합으로 평가할 수 있는
        // 날이 하루도 없으면 탐색을 시작해 봐야 후보가 전부 실패한다. 004 접수와 같은 판정이다.
        if (!commonStart.isBefore(end)) {
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_HISTORY,
                    "상장일 이후 워밍업을 채우고 나면 평가할 구간이 남지 않습니다: commonStart="
                            + commonStart
                            + ", end="
                            + end);
        }
        if (!start.isBefore(end)) {
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_HISTORY,
                    "요청 시작일 이후 첫 거래일이 끝일 이후입니다: start=" + start + ", end=" + end);
        }
        return new CommonPeriod(start, end, warmup.tradingDays(), warmup.shortenedBy());
    }

    /**
     * 워밍업 거래일 수와 그것을 요구한 축.
     *
     * <p><b>한 전략 안에서는 거래일 축을 더한다.</b> 축마다 최댓값을 구해 그것을 합치고, 전략 사이에서만 큰 쪽을 고른다. 최댓값 하나만 쓰면 관측 기간 120과
     * 최근 구간 제외 21을 함께 고른 후보가 141거래일을 요구하는데 묶음은 120만 싣고 나가, 그 후보가 004 접수에서 거부된다(2026-09-22 확인).
     *
     * <p><b>어느 축이 무슨 뜻인지는 여전히 묻지 않는다</b>(FR-025). 거래일 축이 서로 더해지는지 겹치는지는 004가 아는 것이고, 007은 <b>모자라지
     * 않는 쪽</b>으로 잡는다 — 넉넉하면 평가 시작일이 조금 늦어질 뿐이지만, 모자라면 후보가 아예 실행되지 못한다.
     *
     * <p>축이 없거나 거래일 축이 없으면 0이다 — 파라미터가 없는 전략은 워밍업을 요구하지 않는다.
     */
    private static WarmupRequirement warmupOf(
            SearchSpace space, List<StrategyRegistration> targets) {
        int max = 0;
        String shortenedBy = null;
        for (StrategyRegistration registration : targets) {
            SearchSpace.StrategySpace strategy = space.strategies().get(registration.strategyId());
            if (strategy == null) {
                continue;
            }
            int required = 0;
            String longestAxis = null;
            int longestAxisDays = 0;
            for (SearchSpace.Axis axis : strategy.axes()) {
                if (!isWarmupAxis(registration, axis.name())) {
                    continue;
                }
                int axisMax = 0;
                for (Object value : axis.values()) {
                    axisMax = Math.max(axisMax, asTradingDays(value));
                }
                required += axisMax;
                if (axisMax > longestAxisDays) {
                    longestAxisDays = axisMax;
                    longestAxis = axis.name();
                }
            }
            if (required > max) {
                max = required;
                // 범위를 좁혀 구간을 되찾으려는 사용자에게 가장 효과가 큰 축을 가리킨다.
                shortenedBy = longestAxis;
            }
        }
        return new WarmupRequirement(max, shortenedBy);
    }

    /**
     * 이 축이 과거 자료를 요구하는 축인가. 단위가 등록표에 적힌 그대로 쓰인다 — 007이 축 이름을 해석하지 않는다(FR-025).
     *
     * <p>단위를 등록표가 주지 않으면 워밍업 축이 아니라고 본다. 추측해서 잡아 두면 후보와 무관한 구간이 잘려 나간다.
     */
    private static boolean isWarmupAxis(StrategyRegistration registration, String axisName) {
        return registration
                .axis(axisName)
                .map(ParameterAxis::unit)
                .filter(unit -> unit != null && !unit.isBlank())
                .map(TRADING_DAY_UNIT::equals)
                .orElse(false);
    }

    /** 등록표가 거래일 단위를 나타낼 때 쓰는 표기. 값의 뜻은 등록표가 정하고 007은 문자열만 맞춰 본다. */
    private static final String TRADING_DAY_UNIT = "거래일";

    private static int asTradingDays(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            // 거래일 단위라고 등록된 축의 값이 수치가 아니면 등록표가 잘못된 것이다. 추측해서 0으로
            // 넘기면 워밍업 없이 계산되어 결과가 조용히 틀어진다.
            throw new IllegalStateException("trading-day axis must carry numbers: " + value, e);
        }
    }

    private LocalDate latestListingDate(List<String> instrumentIds) {
        return instrumentIds.stream()
                .map(this::listedOn)
                .max(LocalDate::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("instrumentIds must not be empty"));
    }

    private LocalDate listedOn(String instrumentId) {
        Optional<LocalDate> listed = marketMetadata.listedOn(instrumentId);
        return listed.orElseThrow(
                () ->
                        new BusinessException(
                                ErrorCode.INVALID_INPUT, "상장일을 알 수 없는 종목입니다: " + instrumentId));
    }

    /**
     * @param warmupTradingDays 모든 후보와 기준에 똑같이 적용할 워밍업 거래일 수
     * @param shortenedBy 구간을 줄인 축 이름. 워밍업이 0이면 null이다
     */
    public record CommonPeriod(
            LocalDate start, LocalDate end, int warmupTradingDays, String shortenedBy) {}

    private record WarmupRequirement(int tradingDays, String shortenedBy) {}
}
