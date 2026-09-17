package com.ssafy.thorp.paramsearch.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.application.service.EffectivePeriodCalculator;
import com.ssafy.thorp.backtest.domain.model.RulesStatus;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.backtest.infrastructure.marketdata.FixtureMarketMetadataAdapter;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
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
 * 007이 모든 후보에 똑같이 적용할 탐색 구간을 어떻게 정하는지 본다.
 *
 * <p><b>탐색 구간 시작일 = 요청 시작일 당일 또는 그 뒤 첫 거래일.</b> 워밍업만큼 뒤로 늦추지 않는다(001 {@code
 * contracts/market-dataset.md} 「읽기 규칙」). 004 {@code EffectivePeriodCalculator}와 같은 규칙이며, <b>같은 요청이
 * 화면에 따라 다른 구간을 내지 않는지</b>가 여기서 지켜야 할 것이다. 요청 시작일이 없을 때만 상장일 기준값이 제안값으로 나간다.
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
    @DisplayName("요청 시작일이 상장일보다 앞이어도 늦추지 않는다 — 004 접수와 같은 날이 나와야 한다")
    void aLateListingNoLongerPushesTheStart() {
        // BIO 는 2023-07-03 상장이라 요청 앞부분에 읽을 자료가 없다. 그래도 여기서 늦추지 않는다.
        // 늦추면 같은 조건을 단건으로 넣었을 때와 탐색으로 넣었을 때의 구간이 갈리고, 사용자는 그
        // 차이의 까닭을 알 수 없다. 앞이 모자란 만큼 뒤로 미루는 일은 워커가 단독으로 맡는다.
        CommonPeriodResolver.CommonPeriod period =
                resolve(
                        List.of(SEMICONDUCTOR, BIO),
                        LocalDate.of(2023, 1, 2),
                        LocalDate.of(2024, 12, 31));

        assertThat(period.start()).isEqualTo(LocalDate.of(2023, 1, 2));
    }

    @Test
    @DisplayName("같은 조건이면 004 접수와 탐색이 같은 시작일을 낸다")
    void theSearchWindowAgreesWithTheSingleRunIntake() {
        EffectivePeriodCalculator intake =
                new EffectivePeriodCalculator(
                        new FixtureMarketMetadataAdapter(LocalDate.of(2026, 12, 31)));
        StrategyRules rules =
                new StrategyRules(
                        STRATEGY,
                        "1",
                        RulesStatus.REGISTERED,
                        true,
                        Map.of("lookbackDays", 63),
                        null);
        LocalDate start = LocalDate.of(2023, 1, 2);
        LocalDate end = LocalDate.of(2024, 12, 31);

        LocalDate searchStart = resolve(List.of(SEMICONDUCTOR, BIO), start, end).start();
        LocalDate intakeStart =
                intake.resolve(rules, List.of(SEMICONDUCTOR, BIO), start, end, 63).effectiveStart();

        assertThat(searchStart).isEqualTo(intakeStart);
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

    @Test
    @DisplayName("구간 안에 평가할 수 있는 날이 하루도 없으면 거절한다 — 늦추지 않게 됐어도 이 경계는 남는다")
    void aWindowWithNoEvaluableDayIsRejected() {
        // BIO(2023-07-03 상장) + 관측 63거래일이면 평가할 수 있는 첫날이 2023-09-28 인데 끝일이 8월이다.
        // 탐색을 시작해 봐야 후보가 전부 실패하므로 여기서 사유를 알려 준다.
        assertThatThrownBy(
                        () ->
                                resolve(
                                        List.of(SEMICONDUCTOR, BIO),
                                        LocalDate.of(2023, 1, 2),
                                        LocalDate.of(2023, 8, 31)))
                .isInstanceOf(BusinessException.class)
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_HISTORY);
    }
}
