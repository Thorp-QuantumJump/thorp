package com.ssafy.thorp.paramsearch.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.infrastructure.marketdata.FixtureMarketMetadataAdapter;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import com.ssafy.thorp.strategy.application.port.AllowedRange;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 007 공통 평가 구간이 004·워커와 같은 한 규칙을 쓰는지 본다.
 *
 * <p><b>평가 시작일 = max(요청 시작일, 가장 늦은 상장일 이후 워밍업 {@code warmup}번째 거래일).</b> 워밍업 기준점은 상장일 하나이고 요청 시작일은
 * 마지막에 max 로만 합쳐진다(001 {@code contracts/evaluation-window.md}). 이 두 항의 경계를 각각 덮는다 — 여기가 어긋나면 007
 * 묶음의 구간과 004 접수의 {@code effectiveStart}가 갈라져 나란히 놓은 숫자가 다른 구간의 값이 된다.
 */
class CommonPeriodResolverTest {

    private static final String SEMICONDUCTOR = "091160.XKRX"; // 2015-01-02 상장
    private static final String BIO = "244580.XKRX"; // 2023-07-03 상장

    private static final String STRATEGY = "INVERSE_VOLATILITY";
    private static final String AXIS = "lookbackDays";

    /** 상장일 2023-07-03 + 63거래일(픽스처 달력은 주말만 제외). 계약 {@code commonStart}와 같은 값이다. */
    private static final LocalDate BIO_COMMON_START = LocalDate.of(2023, 9, 28);

    /** 픽스처 달력·상장일을 그대로 쓴다 — 가짜 포트를 만들면 004와 달력이 갈린다. */
    private final CommonPeriodResolver resolver =
            new CommonPeriodResolver(
                    new FixtureMarketMetadataAdapter(LocalDate.of(2026, 12, 31)),
                    Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Seoul")));

    /** 관측 길이 축 하나. 단위가 "거래일"이라야 007이 워밍업 축으로 본다(FR-025). */
    private static final StrategyRegistration REGISTRATION =
            new StrategyRegistration(
                    STRATEGY,
                    "역변동성",
                    null,
                    List.of(
                            new ParameterAxis(
                                    AXIS,
                                    "관측 기간",
                                    null,
                                    "거래일",
                                    List.of(20, 63),
                                    20,
                                    AllowedRange.ofValues(List.of(20, 63)))),
                    true,
                    null,
                    "COMPUTED");

    private static final SearchSpace SPACE =
            SearchSpace.of(
                    Map.of(
                            STRATEGY,
                            SearchSpace.StrategySpace.of(
                                    "역변동성",
                                    List.of(
                                            new SearchSpace.Axis(
                                                    AXIS, "관측 기간", "거래일", List.of(20, 63))))));

    private CommonPeriodResolver.CommonPeriod resolve(
            List<String> instrumentIds, LocalDate requestedStart, LocalDate requestedEnd) {
        return resolver.resolve(
                instrumentIds, SPACE, List.of(REGISTRATION), requestedStart, requestedEnd);
    }

    @Test
    @DisplayName("요청 시작일이 상장일보다 한참 뒤면 워밍업이 요청 구간을 깎지 않는다")
    void warmupBeforeTheRequestDoesNotPushTheStart() {
        // 2015년 상장 종목을 2024-01-02 부터 요청한다. 워밍업 63거래일은 2023년에 있으므로 워커가
        // 요청 구간 앞에서 읽는다. 예전 구현은 이 63일을 요청 구간에서 깎아 2024년 4월부터 비교했다.
        CommonPeriodResolver.CommonPeriod period =
                resolve(
                        List.of(SEMICONDUCTOR),
                        LocalDate.of(2024, 1, 2),
                        LocalDate.of(2024, 12, 31));

        assertThat(period.start()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(period.warmupTradingDays()).isEqualTo(63);
        assertThat(period.shortenedBy()).isEqualTo(AXIS);
    }

    @Test
    @DisplayName("요청 시작일이 상장일보다 앞이면 상장일 이후 워밍업 번째 거래일부터다")
    void warmupAfterALateListingPushesTheStart() {
        // BIO 는 2023-07-03 상장이라 그 앞에는 읽을 자료가 없다. 밀리는 양은 계약 commonStart 와 같다.
        CommonPeriodResolver.CommonPeriod period =
                resolve(
                        List.of(SEMICONDUCTOR, BIO),
                        LocalDate.of(2023, 1, 2),
                        LocalDate.of(2024, 12, 31));

        assertThat(period.start()).isEqualTo(BIO_COMMON_START);
    }

    @Test
    @DisplayName("요청 시작일이 없으면 상장일 기준값을 서버가 제시한다")
    void withoutARequestedStartTheListingBasedValueIsUsed() {
        CommonPeriodResolver.CommonPeriod period =
                resolve(List.of(SEMICONDUCTOR, BIO), null, LocalDate.of(2024, 12, 31));

        assertThat(period.start()).isEqualTo(BIO_COMMON_START);
    }

    @Test
    @DisplayName("요청 시작일을 뒤로 옮겨도 상장일 기준값은 꿈쩍하지 않는다 — 기준점은 상장일 하나다")
    void theListingBasedValueDoesNotMoveWithTheRequest() {
        // 요청을 상장일 기준값보다 뒤로 옮기면 그 요청일이 그대로 시작일이다. 상장일 기준값을 한 번 더
        // 소비해 2024년 3월쯤으로 밀리면 안 된다.
        CommonPeriodResolver.CommonPeriod period =
                resolve(List.of(BIO), LocalDate.of(2024, 1, 2), LocalDate.of(2024, 12, 31));

        assertThat(period.start()).isEqualTo(LocalDate.of(2024, 1, 2));
    }

    @Test
    @DisplayName("요청 시작일이 휴장일이면 그 뒤 첫 거래일로 맞춘다 — 004 접수가 고르는 날과 같아야 한다")
    void requestedStartIsSnappedToATradingDay() {
        CommonPeriodResolver.CommonPeriod period =
                resolve(
                        List.of(SEMICONDUCTOR),
                        LocalDate.of(2024, 6, 1), // 토요일
                        LocalDate.of(2024, 12, 31));

        assertThat(period.start()).isEqualTo(LocalDate.of(2024, 6, 3));
    }
}
