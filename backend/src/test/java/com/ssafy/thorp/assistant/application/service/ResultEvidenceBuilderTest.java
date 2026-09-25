package com.ssafy.thorp.assistant.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.assistant.application.exception.ToolUnavailableException;
import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.assistant.application.port.AssistantTool;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceBundle;
import com.ssafy.thorp.assistant.application.result.PageContext;
import com.ssafy.thorp.assistant.application.result.PageKind;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import com.ssafy.thorp.backtest.application.repository.CostProfileRepository;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.application.result.BacktestWeightsResult;
import com.ssafy.thorp.backtest.domain.model.CostProfile;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.result.application.result.PublicResultDetail;
import com.ssafy.thorp.result.application.result.ValidationTier;
import com.ssafy.thorp.result.application.service.PublicResultService;
import com.ssafy.thorp.sector.application.exception.SectorMapNotReadyException;
import com.ssafy.thorp.sector.application.result.SectorListResult;
import com.ssafy.thorp.sector.application.service.SectorService;
import com.ssafy.thorp.sector.domain.model.Sector;
import com.ssafy.thorp.sector.domain.vo.SectorCode;
import com.ssafy.thorp.strategy.application.port.AllowedRange;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.RegisteredChoice;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 계약 §2.1 포함 표(T030). 표의 항목만, 한국어 라벨로, 60개 안에서. */
class ResultEvidenceBuilderTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final Set<String> TABS = Set.of("summary", "analysis", "settings", "trades", "loss");
    static final AssistantSettings SETTINGS =
            new AssistantSettings(
                    Set.of(PageKind.RESULT),
                    8,
                    5,
                    1000,
                    300,
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(30),
                    2,
                    60,
                    30,
                    600,
                    5);

    private final PublicResultService publicResults = mock(PublicResultService.class);
    private final SectorService sectors = mock(SectorService.class);
    private final ParameterRegistryPort registry = mock(ParameterRegistryPort.class);
    private final CostProfileRepository costProfiles = mock(CostProfileRepository.class);
    private final ResultEvidenceBuilder builder =
            new ResultEvidenceBuilder(
                    publicResults, sectors, registry, costProfiles, SETTINGS, 100);

    @BeforeEach
    void stubs() {
        Sector semiconductor = mock(Sector.class);
        when(semiconductor.code()).thenReturn(new SectorCode("SEMICONDUCTOR"));
        when(semiconductor.name()).thenReturn("반도체");
        Sector battery = mock(Sector.class);
        when(battery.code()).thenReturn(new SectorCode("BATTERY"));
        when(battery.name()).thenReturn("이차전지");
        when(sectors.getSectors())
                .thenReturn(
                        new SectorListResult(
                                "SECTOR-ETF-2026-09", false, List.of(semiconductor, battery)));
        ParameterAxis axis = mock(ParameterAxis.class);
        when(axis.name()).thenReturn("rebalance");
        when(axis.label()).thenReturn("리밸런싱 주기");
        when(axis.unit()).thenReturn(null);
        StrategyRegistration registration = mock(StrategyRegistration.class);
        when(registration.applicableAxes(any())).thenReturn(List.of(axis));
        when(registry.find("EQUAL_WEIGHT")).thenReturn(Optional.of(registration));
        when(costProfiles.find("KR-ETF-2026-09"))
                .thenReturn(
                        Optional.of(
                                new CostProfile(
                                        "KR-ETF-2026-09",
                                        new BigDecimal("1.5"),
                                        new BigDecimal("3"),
                                        new BigDecimal("5"),
                                        BigDecimal.ZERO,
                                        null,
                                        false,
                                        "PD-07",
                                        "국내 상장 ETF 매도에는 증권거래세가 없어 0으로 둔다")));
    }

    static Map<String, Object> item(String sectorId, String instrumentId, String weight) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sectorId", sectorId);
        item.put("instrumentId", instrumentId);
        item.put("targetWeight", weight);
        return item;
    }

    static Map<String, Object> config(List<Map<String, Object>> items) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("strategyId", "EQUAL_WEIGHT");
        config.put("parameters", Map.of("rebalance", "MONTH_END"));
        config.put("items", items);
        config.put("start", "2023-07-01");
        config.put("end", "2024-12-31");
        config.put("effectiveStart", "2023-07-03");
        config.put("effectiveEnd", "2024-12-31");
        config.put("warmupTradingDays", 0);
        config.put("initialCash", new BigDecimal("11110000.00"));
        return config;
    }

    static BacktestDetailResult.Summary summary(List<String> instruments) {
        Map<String, BigDecimal> risk = new LinkedHashMap<>();
        Map<String, BigDecimal> loss = new LinkedHashMap<>();
        Map<String, Map<String, BigDecimal>> corr = new LinkedHashMap<>();
        for (String id : instruments) {
            risk.put(id, new BigDecimal("0.5"));
            loss.put(id, new BigDecimal("0.5"));
            corr.put(id, Map.of(id, BigDecimal.ONE));
        }
        return new BacktestDetailResult.Summary(
                new BigDecimal("11110000.00"),
                new BigDecimal("12961538.71"),
                "KRW",
                new BigDecimal("0.168000"),
                new BigDecimal("0.110000"),
                new BigDecimal("-0.041757"),
                new BigDecimal("1.2345"),
                new BigDecimal("0.150000"),
                24,
                new BigDecimal("0.583333"),
                new BigDecimal("12000"),
                new BigDecimal("5078"),
                new BigDecimal("0.166652"),
                new BigDecimal("17078.49"),
                new EngineOutput.Costs(
                        new BigDecimal("12000"),
                        new BigDecimal("0.00"),
                        new BigDecimal("5078.49"),
                        new BigDecimal("0.00"),
                        new BigDecimal("17078.49")),
                null,
                new EngineOutput.Analytics(
                        9,
                        new BigDecimal("1.219991"),
                        corr,
                        risk,
                        loss,
                        new BigDecimal("0.000000"),
                        "sample-covariance-daily"),
                1);
    }

    static PublicResultDetail detail(
            Map<String, Object> config, BacktestDetailResult.Summary summary, boolean partial) {
        return new PublicResultDetail(
                TOKEN,
                partial ? RunStatus.CANCELLED : RunStatus.COMPLETED,
                ResultSource.NEW,
                partial,
                false,
                Instant.parse("2026-09-30T00:00:00Z"),
                null,
                Instant.parse("2026-09-24T00:00:00Z"),
                null,
                null,
                null,
                config,
                Map.of(
                        "engine",
                        "nautilus 1.219.0",
                        "rules",
                        Map.of("version", "1"),
                        "costProfile",
                        Map.of("version", "KR-ETF-2026-09")),
                summary,
                null,
                null,
                ValidationTier.UNVALIDATED,
                "똑같이 나누기",
                WeightsSource.COMPUTED,
                List.of("HALT_DEFERRED"));
    }

    static BacktestWeightsResult weights(List<String> instruments) {
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        for (String id : instruments) {
            actual.put(id, new BigDecimal("0.4000"));
        }
        return new BacktestWeightsResult(
                "BT-20260907-000001",
                false,
                WeightsSource.COMPUTED,
                List.of(
                        new EngineOutput.WeightsPoint(
                                LocalDate.of(2024, 6, 28),
                                Map.of(),
                                Map.of("091160.XKRX", BigDecimal.ONE),
                                null,
                                null,
                                null),
                        new EngineOutput.WeightsPoint(
                                LocalDate.of(2024, 12, 31),
                                Map.of(),
                                actual,
                                BigDecimal.ZERO,
                                null,
                                null)));
    }

    static List<Map<String, Object>> twoItems() {
        return List.of(
                item("SEMICONDUCTOR", "091160.XKRX", "0.4000"),
                item("BATTERY", "305720.XKRX", "0.6000"));
    }

    private EvidenceBundle buildCompleted() {
        when(publicResults.getWeights(TOKEN))
                .thenReturn(weights(List.of("091160.XKRX", "305720.XKRX")));
        when(publicResults.getDetail(TOKEN))
                .thenReturn(
                        detail(
                                config(twoItems()),
                                summary(List.of("091160.XKRX", "305720.XKRX")),
                                false));
        return builder.build(new PageContext(PageKind.RESULT, TOKEN));
    }

    static Map<String, Evidence> byLabel(EvidenceBundle bundle) {
        Map<String, Evidence> map = new LinkedHashMap<>();
        for (Evidence fact : bundle.facts()) {
            map.put(fact.label(), fact);
        }
        return map;
    }

    @Test
    @DisplayName("초기 팩은 포함 표의 항목만 한국어 라벨로 담고, 값은 원문이며 표기는 formatResult.ts 와 같다")
    void packMatchesInclusionTable() {
        EvidenceBundle bundle = buildCompleted();
        Map<String, Evidence> facts = byLabel(bundle);

        assertThat(facts.keySet())
                .containsExactlyInAnyOrder(
                        "시작 자산",
                        "종료 자산",
                        "전체 수익률",
                        "비용 차감 후 전체 수익률",
                        "연복리 수익률",
                        "가장 크게 줄었던 폭",
                        "변동성",
                        "승률",
                        "샤프 비율",
                        "거래 횟수",
                        "거래비용 합계",
                        "수수료",
                        "스프레드",
                        "슬리피지",
                        "세금",
                        "세금 규칙",
                        "회복 기간(거래일)",
                        "회전율",
                        "비중 오차",
                        "추정 방법",
                        "요청 시작일",
                        "요청 종료일",
                        "평가 시작일",
                        "평가 종료일",
                        "준비 기간(거래일)",
                        "시작 금액",
                        "전략",
                        "리밸런싱 주기",
                        "반도체 대표 ETF",
                        "반도체 목표 비중",
                        "이차전지 대표 ETF",
                        "이차전지 목표 비중",
                        "비중 출처",
                        "반도체 실제 비중",
                        "이차전지 실제 비중",
                        "반도체 위험 기여",
                        "이차전지 위험 기여",
                        "반도체 손실 기여",
                        "이차전지 손실 기여",
                        "계산 중 예외");
        assertThat(facts.get("비용 차감 후 전체 수익률").value()).isEqualTo("0.166652");
        assertThat(facts.get("비용 차감 후 전체 수익률").formattedValue()).isEqualTo("16.67%");
        assertThat(facts.get("거래비용 합계").formattedValue()).isEqualTo("17,078원");
        assertThat(facts.get("샤프 비율").formattedValue()).isEqualTo("1.23");
        assertThat(facts.get("거래 횟수").formattedValue()).isEqualTo("24");
        assertThat(facts.get("평가 종료일").formattedValue()).isEqualTo("2024-12-31");
        assertThat(facts.get("시작 금액").formattedValue()).isEqualTo("11,110,000원");
        assertThat(facts.get("전략").value()).isEqualTo("똑같이 나누기");
        assertThat(facts.get("리밸런싱 주기").value()).isEqualTo("MONTH_END");
        assertThat(facts.get("세금 규칙").value()).isEqualTo("국내 상장 ETF 매도에는 증권거래세가 없어 0으로 둔다");
        assertThat(facts.get("세금 규칙").tab()).isEqualTo("settings");
        assertThat(facts.get("비중 출처").value()).isEqualTo("전략 계산");
        assertThat(facts.get("반도체 실제 비중").asOf()).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(facts.get("반도체 실제 비중").value()).isEqualTo("0.4000");
        assertThat(facts.get("계산 중 예외").value()).isEqualTo("HALT_DEFERRED");
        assertThat(bundle.facts()).allSatisfy(fact -> assertThat(TABS).contains(fact.tab()));
        assertThat(bundle.facts()).allSatisfy(fact -> assertThat(fact.id()).isNull());
        assertThat(bundle.partialUntil()).isNull();
        verify(publicResults, never()).getTimeseries(anyString());
    }

    @Test
    @DisplayName("공개 토큰·만료·버전·진행·backtestId·상관 행렬은 어디에도 없다")
    void packCarriesNoIdentifiersOrExcludedFields() {
        EvidenceBundle bundle = buildCompleted();

        String everything = bundle.facts().toString();
        assertThat(everything)
                .doesNotContain(
                        TOKEN,
                        "BT-",
                        "backtestId",
                        "publicToken",
                        "expiresAt",
                        "nautilus",
                        "2026-09-30",
                        "상관");
        assertThat(byLabel(bundle).keySet()).noneMatch(label -> label.matches(".*[a-z][A-Z].*"));
    }

    @Test
    @DisplayName("첫 조회는 getWeights 라 결과 없음(409)이면 상세를 읽지 않고 그대로 던진다")
    void notReadyPropagatesBeforeDetail() {
        when(publicResults.getWeights(TOKEN))
                .thenThrow(new BusinessException(ErrorCode.RESULT_NOT_READY));

        assertThatThrownBy(() -> builder.build(new PageContext(PageKind.RESULT, TOKEN)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exc ->
                                assertThat(exc.getErrorCode())
                                        .isEqualTo(ErrorCode.RESULT_NOT_READY));
        verify(publicResults, never()).getDetail(anyString());
    }

    @Test
    @DisplayName("섹터 8개여도 60개 이하 — 위험·손실 기여를 빼고 '일부 분석 값은 싣지 않음' 근거를 남기며 비중은 다 싣는다")
    void trimsContributionsAtCapacity() {
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> instruments = new ArrayList<>();
        IntStream.range(0, 8)
                .forEach(
                        i -> {
                            String id = "00000" + i + ".XKRX";
                            instruments.add(id);
                            items.add(item("SECTOR_" + i, id, "0.125"));
                        });
        when(publicResults.getWeights(TOKEN)).thenReturn(weights(instruments));
        when(publicResults.getDetail(TOKEN))
                .thenReturn(detail(config(items), summary(instruments), false));

        EvidenceBundle bundle = builder.build(new PageContext(PageKind.RESULT, TOKEN));

        assertThat(bundle.facts()).hasSizeLessThanOrEqualTo(60);
        Map<String, Evidence> facts = byLabel(bundle);
        assertThat(facts.keySet())
                .noneMatch(label -> label.endsWith("위험 기여") || label.endsWith("손실 기여"));
        assertThat(facts).containsKey(ResultEvidenceBuilder.TRIMMED_LABEL);
        assertThat(facts.keySet().stream().filter(label -> label.endsWith("실제 비중"))).hasSize(8);
        assertThat(facts.keySet().stream().filter(label -> label.endsWith("목표 비중"))).hasSize(8);
        assertThat(facts.get("SECTOR_7 대표 ETF").value()).isEqualTo("000007.XKRX");
    }

    @Test
    @DisplayName("부분 결과면 시계열 마지막 날짜가 완료 끝 날짜이고, 도구도 그 날짜 뒤를 읽지 않는다")
    void partialUsesLastTimeseriesDate() {
        when(publicResults.getWeights(TOKEN)).thenReturn(weights(List.of("091160.XKRX")));
        when(publicResults.getDetail(TOKEN))
                .thenReturn(detail(config(twoItems()), summary(List.of("091160.XKRX")), true));
        when(publicResults.getTimeseries(TOKEN))
                .thenReturn(
                        new BacktestTimeseriesResult(
                                "BT-1",
                                true,
                                List.of(
                                        new BacktestTimeseriesResult.Point(
                                                LocalDate.of(2024, 3, 4),
                                                BigDecimal.TEN,
                                                BigDecimal.ZERO,
                                                BigDecimal.ZERO,
                                                null,
                                                null),
                                        new BacktestTimeseriesResult.Point(
                                                LocalDate.of(2024, 3, 5),
                                                BigDecimal.TEN,
                                                BigDecimal.ZERO,
                                                BigDecimal.ZERO,
                                                null,
                                                null))));

        EvidenceBundle bundle = builder.build(new PageContext(PageKind.RESULT, TOKEN));

        assertThat(bundle.partialUntil()).isEqualTo(LocalDate.of(2024, 3, 5));
        assertThat(byLabel(bundle).get("완료 끝 날짜").value()).isEqualTo("2024-03-05");
        assertThat(byLabel(bundle)).containsKey("결과 상태");
        AssistantTool equityOn = bundle.tools().get(0);
        assertThatThrownBy(() -> equityOn.run(Map.of("date", "2024-06-03")))
                .isInstanceOf(ToolUnavailableException.class)
                .hasMessageContaining("결과 기간");
        verify(publicResults).getTimeseries(TOKEN);
    }

    @Test
    @DisplayName("섹터 목록·등록표를 못 읽으면 id 원문을 라벨에 쓰고 팩은 만든다")
    void fallsBackToIdsWhenNamesUnavailable() {
        when(sectors.getSectors()).thenThrow(new SectorMapNotReadyException());
        when(registry.find(anyString())).thenReturn(Optional.empty());

        Map<String, Evidence> facts = byLabel(buildCompleted());

        assertThat(facts).containsKeys("SEMICONDUCTOR 대표 ETF", "BATTERY 실제 비중", "rebalance");
    }

    @Test
    @DisplayName("목록형 축의 객체 값은 등록표 선택지 문구로 적고, 프로필이 없으면 세금 규칙 근거도 없다")
    void choiceValuesUseRegistryLabels() {
        ParameterAxis axis = mock(ParameterAxis.class);
        when(axis.name()).thenReturn("rebalance");
        when(axis.label()).thenReturn("리밸런싱 주기");
        when(axis.unit()).thenReturn(null);
        when(axis.allowedRange())
                .thenReturn(
                        AllowedRange.ofChoices(
                                List.of(
                                        new RegisteredChoice(
                                                Map.of("mode", "CALENDAR", "unit", "MONTH_END"),
                                                "월말"))));
        StrategyRegistration registration = mock(StrategyRegistration.class);
        when(registration.applicableAxes(any())).thenReturn(List.of(axis));
        when(registry.find("EQUAL_WEIGHT")).thenReturn(Optional.of(registration));
        when(costProfiles.find(anyString())).thenReturn(Optional.empty());
        Map<String, Object> config = config(twoItems());
        config.put(
                "parameters", Map.of("rebalance", Map.of("mode", "CALENDAR", "unit", "MONTH_END")));
        when(publicResults.getWeights(TOKEN))
                .thenReturn(weights(List.of("091160.XKRX", "305720.XKRX")));
        when(publicResults.getDetail(TOKEN))
                .thenReturn(detail(config, summary(List.of("091160.XKRX", "305720.XKRX")), false));

        Map<String, Evidence> facts =
                byLabel(builder.build(new PageContext(PageKind.RESULT, TOKEN)));

        assertThat(facts.get("리밸런싱 주기").value()).isEqualTo("월말");
        assertThat(facts).doesNotContainKey("세금 규칙");
    }

    @Test
    @DisplayName("도구 다섯 개가 이 요청의 결과에 묶여 만들어지고 인자는 날짜뿐이다")
    void bundlesFiveDateOnlyTools() {
        EvidenceBundle bundle = buildCompleted();

        List<ToolSpec> specs = bundle.tools().stream().map(AssistantTool::spec).toList();
        assertThat(specs)
                .extracting(ToolSpec::name)
                .containsExactly(
                        "getEquityOn",
                        "getEquityRange",
                        "getTradesBetween",
                        "getPositionsOn",
                        "getDeepestDrawdownWindow");
        assertThat(specs)
                .allSatisfy(
                        spec ->
                                assertThat(spec.dateArguments())
                                        .allMatch(
                                                arg -> Set.of("date", "from", "to").contains(arg)));
    }
}
