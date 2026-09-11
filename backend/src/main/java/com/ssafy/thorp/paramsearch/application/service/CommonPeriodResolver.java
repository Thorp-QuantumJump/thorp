package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.application.port.ParameterAxis;
import com.ssafy.thorp.paramsearch.application.port.StrategyRegistration;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
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
     * @param requestedStart 사용자가 고른 시작일. 없으면 상장일 기준으로 서버가 제시한다
     * @param requestedEnd 사용자가 고른 끝일. 없으면 오늘
     */
    public CommonPeriod resolve(
            List<String> instrumentIds,
            SearchSpace space,
            List<StrategyRegistration> targets,
            LocalDate requestedStart,
            LocalDate requestedEnd) {
        LocalDate listedFrom = latestListingDate(instrumentIds);
        LocalDate notBefore =
                requestedStart == null ? listedFrom : maxOf(requestedStart, listedFrom);
        LocalDate end = requestedEnd == null ? LocalDate.now(clock) : requestedEnd;

        WarmupRequirement warmup = warmupOf(space, targets);
        LocalDate start =
                marketMetadata
                        .evaluationStart(notBefore, warmup.tradingDays())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INSUFFICIENT_HISTORY,
                                                "공통 평가 구간을 만들 수 있는 과거 자료가 부족합니다: 워밍업 "
                                                        + warmup.tradingDays()
                                                        + "거래일"));
        if (!start.isBefore(end)) {
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_HISTORY,
                    "워밍업을 소진하면 평가할 구간이 남지 않습니다: start=" + start + ", end=" + end);
        }
        return new CommonPeriod(start, end, warmup.tradingDays(), warmup.shortenedBy());
    }

    /**
     * 워밍업 거래일 수와 그것을 요구한 축.
     *
     * <p>수치 축의 최댓값 중 가장 큰 값을 쓴다. 축이 없거나 수치 축이 없으면 0이다 — 파라미터가 없는 전략은 워밍업을 요구하지 않는다.
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
            for (SearchSpace.Axis axis : strategy.axes()) {
                if (!isWarmupAxis(registration, axis.name())) {
                    continue;
                }
                for (Object value : axis.values()) {
                    int candidate = asTradingDays(value);
                    if (candidate > max) {
                        max = candidate;
                        shortenedBy = axis.name();
                    }
                }
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

    private static LocalDate maxOf(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? left : right;
    }

    /**
     * @param warmupTradingDays 모든 후보와 기준에 똑같이 적용할 워밍업 거래일 수
     * @param shortenedBy 구간을 줄인 축 이름. 워밍업이 0이면 null이다
     */
    public record CommonPeriod(
            LocalDate start, LocalDate end, int warmupTradingDays, String shortenedBy) {}

    private record WarmupRequirement(int tradingDays, String shortenedBy) {}
}
