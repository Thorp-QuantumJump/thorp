package com.ssafy.thorp.sector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.sector.application.command.EvaluationWindowQuery;
import com.ssafy.thorp.sector.application.exception.EvaluationWindowNotReadyException;
import com.ssafy.thorp.sector.application.exception.SectorMapNotReadyException;
import com.ssafy.thorp.sector.application.exception.SectorNotFoundException;
import com.ssafy.thorp.sector.application.repository.InstrumentProfileRepository;
import com.ssafy.thorp.sector.application.result.EvaluationWindowInputs;
import com.ssafy.thorp.sector.application.result.EvaluationWindowView;
import com.ssafy.thorp.sector.application.result.SectorListResult;
import com.ssafy.thorp.sector.application.service.EvaluationWindowQueryService;
import com.ssafy.thorp.sector.application.service.EvaluationWindowService;
import com.ssafy.thorp.sector.application.service.SectorService;
import com.ssafy.thorp.sector.domain.model.RepresentativeEtf;
import com.ssafy.thorp.sector.domain.model.Sector;
import com.ssafy.thorp.sector.domain.vo.SectorCode;
import com.ssafy.thorp.strategy.application.port.AnalysisConditionRegistry;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.SearchContext;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import com.ssafy.thorp.strategy.application.port.ValidationRule;
import com.ssafy.thorp.strategy.application.port.ValidationVerdict;
import com.ssafy.thorp.strategy.application.port.WarmupRule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공통 평가 가능 기간 조회의 판정을 검증한다 (spec 001 T017, FR-007).
 *
 * <p>산식 자체는 {@link EvaluationWindowTest}가 워커와 공유하는 픽스처로 이미 검증한다. 여기서 확인하는 것은 <b>그 산식을 HTTP 표면으로
 * 내보내며 더한 것</b>이다 — 화면이 값을 바꿀 때마다 서버를 다시 부르지 않고 스스로 재판정할 수 있게 실어 보내는 달력 조각, 달력이 모자랄 때의 처리, 그리고 전략을
 * 주지 않은 요청이다.
 *
 * <p><b>가장 중요한 단언은 "화면의 계산과 서버의 계산이 같은 날을 가리킨다"이다.</b> 003 화면은 {@code commonStart(N) =
 * tradingDaysFromBase.get(N)}으로 계산하기로 했으므로, 그 식이 서버가 워밍업 N으로 직접 계산한 {@code commonStart}와 어긋나면 화면은
 * 실제로 분석할 수 없는 기간을 안내하게 된다.
 *
 * <p><b>순수 단위 테스트다.</b> DB도 스프링 컨텍스트도 쓰지 않는다 — 저장소는 손으로 만든 스텁이고, 확인하려는 것이 조립 규칙이지 SQL이 아니다. SQL과 계약
 * 모양은 {@code EvaluationWindowApiIT}가 실제 자료로 확인한다.
 */
class EvaluationWindowQueryServiceTest {

    private static final String SEMICONDUCTOR = "SEMICONDUCTOR";
    private static final String BATTERY = "BATTERY";
    private static final String SEMICONDUCTOR_ETF = "091160.XKRX";
    private static final String BATTERY_ETF = "305720.XKRX";
    private static final String DATASET_ID = "KRX_DAILY_20260915";
    private static final String MAP_VERSION = "SEM-2026-09-15-001";
    private static final String MOMENTUM = "SECTOR_MOMENTUM";

    /** 2024-01-01(월)부터의 평일. 공휴일을 지어내지 않고 주말만 뺀다 — 여기서 검증하는 것은 달력의 내용이 아니라 세는 방식이다. */
    private static final List<LocalDate> CALENDAR = weekdays(LocalDate.of(2024, 1, 1), 400);

    private static final LocalDate DEADLINE = CALENDAR.get(CALENDAR.size() - 1);

    @Test
    @DisplayName("전략 없이 상한만 주면 워밍업 0으로 판정하고 화면이 계산할 거래일 목록을 함께 준다")
    void 전략_없는_요청은_워밍업_0으로_판정한다() {
        EvaluationWindowView view =
                service(inputs()).getEvaluationWindow(query(List.of(SEMICONDUCTOR), null, 100));

        assertThat(view.warmupTradingDays()).isZero();
        assertThat(view.commonStart()).isEqualTo(view.warmupBaseDate());
        assertThat(view.evaluable()).isTrue();
        assertThat(view.tradingDaysFromBase()).hasSize(101).startsWith(view.warmupBaseDate());
        assertThat(view.maxWarmupTradingDays()).isEqualTo(100);
        assertThat(view.datasetId()).isEqualTo(DATASET_ID);
    }

    @Test
    @DisplayName("화면이 목록으로 계산한 시작일은 서버가 그 워밍업으로 직접 계산한 시작일과 같다")
    void 화면의_계산과_서버의_계산이_같은_날을_가리킨다() {
        EvaluationWindowQueryService service = service(inputs());
        EvaluationWindowView base =
                service.getEvaluationWindow(query(List.of(SEMICONDUCTOR, BATTERY), null, 200));

        // 003 화면이 값을 바꿀 때마다 서버를 부르지 않고 하는 계산이 이 한 줄이다.
        for (int warmup : new int[] {0, 1, 63, 147, 200}) {
            LocalDate calculatedByScreen = base.tradingDaysFromBase().get(warmup);
            EvaluationWindowView server =
                    service.getEvaluationWindow(
                            query(List.of(SEMICONDUCTOR, BATTERY), strategyOf(warmup), 200));
            assertThat(server.commonStart()).as("워밍업 %d 거래일", warmup).isEqualTo(calculatedByScreen);
        }
    }

    @Test
    @DisplayName("기준일은 가장 늦게 상장한 후보가 정하고 그 후보를 함께 알려 준다")
    void 기준_후보와_그_섹터를_알려_준다() {
        EvaluationWindowView view =
                service(inputs())
                        .getEvaluationWindow(query(List.of(SEMICONDUCTOR, BATTERY), null, 10));

        // BATTERY가 더 늦게 상장했으므로 그 후보가 기준이다.
        assertThat(view.bindingCandidate()).isEqualTo(BATTERY_ETF);
        assertThat(view.bindingSector()).isEqualTo(BATTERY);
        assertThat(view.warmupBaseDate()).isEqualTo(LocalDate.of(2024, 3, 1));
    }

    @Test
    @DisplayName("달력이 요청한 상한에 모자라면 오류가 아니라 있는 만큼만 주고 상한을 줄인다")
    void 달력이_모자라면_있는_만큼만_준다() {
        EvaluationWindowView view =
                service(inputs()).getEvaluationWindow(query(List.of(BATTERY), null, 5000));

        assertThat(view.tradingDaysFromBase()).isNotEmpty();
        assertThat(view.maxWarmupTradingDays()).isEqualTo(view.tradingDaysFromBase().size() - 1);
        assertThat(view.maxWarmupTradingDays()).isLessThan(5000);
    }

    @Test
    @DisplayName("거래일 목록은 공통 종료일을 넘지 않는다")
    void 목록은_공통_종료일을_넘지_않는다() {
        EvaluationWindowView view =
                service(inputs()).getEvaluationWindow(query(List.of(SEMICONDUCTOR), null, 5000));

        assertThat(view.tradingDaysFromBase())
                .allSatisfy(day -> assertThat(day).isBeforeOrEqualTo(view.commonEnd()));
    }

    @Test
    @DisplayName("워밍업을 채울 거래일이 없으면 오류가 아니라 평가 불가로 답한다")
    void 워밍업을_채우지_못하면_평가_불가다() {
        EvaluationWindowView view =
                service(inputs())
                        .getEvaluationWindow(query(List.of(BATTERY), strategyOf(9999), 100));

        assertThat(view.evaluable()).isFalse();
        assertThat(view.commonStart()).isNull();
        assertThat(view.excludedRanges()).isEmpty();
        // 그래도 재판정 재료는 그대로 준다 — 화면이 더 짧은 워밍업으로 다시 판정할 수 있어야 한다.
        assertThat(view.warmupBaseDate()).isNotNull();
        assertThat(view.tradingDaysFromBase()).isNotEmpty();
    }

    @Test
    @DisplayName("자료 부족 구간은 워밍업 반영본과 기준일부터의 것을 함께 준다")
    void 부족_구간을_두_기준으로_준다() {
        // 기준일(2024-01-01) 직후의 부족 구간. 워밍업 63거래일을 반영하면 잘려 나간다.
        EvaluationWindowInputs withGap =
                inputsWith(
                        List.of(
                                new EvaluationWindowInputs.DataGap(
                                        LocalDate.of(2024, 1, 3),
                                        LocalDate.of(2024, 1, 10),
                                        "AMBIGUOUS_PRICE")));

        EvaluationWindowView view =
                service(withGap)
                        .getEvaluationWindow(query(List.of(SEMICONDUCTOR), strategyOf(63), 100));

        assertThat(view.excludedRanges()).isEmpty();
        assertThat(view.excludedRangesFromBase())
                .singleElement()
                .satisfies(
                        range -> {
                            assertThat(range.start()).isEqualTo(LocalDate.of(2024, 1, 3));
                            assertThat(range.reason()).isEqualTo("AMBIGUOUS_PRICE");
                        });
    }

    @Test
    @DisplayName("상한을 주지 않으면 재판정 재료를 만들지 않는다")
    void 상한을_주지_않으면_재료를_내리지_않는다() {
        EvaluationWindowView view =
                service(inputs())
                        .getEvaluationWindow(query(List.of(SEMICONDUCTOR), MOMENTUM, null));

        assertThat(view.tradingDaysFromBase()).isNull();
        assertThat(view.maxWarmupTradingDays()).isNull();
        assertThat(view.excludedRangesFromBase()).isNull();
        // 기준일은 상한과 무관하게 늘 채운다. 화면이 "워밍업 때문에 줄었다"를 설명하는 값이다(FR-011).
        assertThat(view.warmupBaseDate()).isNotNull();
    }

    @Test
    @DisplayName("섹터도 전략도 값이 없으면 판정할 것이 없으므로 요청 자체를 거부한다")
    void 전략과_상한이_모두_없으면_거부한다() {
        assertThatThrownBy(() -> query(List.of(SEMICONDUCTOR), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(List.of(), null, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("열린 대응표에 없는 섹터는 404로 가른다")
    void 없는_섹터는_따로_가른다() {
        assertThatThrownBy(
                        () ->
                                service(inputs())
                                        .getEvaluationWindow(query(List.of("NO_SUCH"), null, 10)))
                .isInstanceOf(SectorNotFoundException.class);
    }

    @Test
    @DisplayName("자료 버전이나 달력이 아직 없으면 빈 기간이 아니라 재시도 가능한 실패다")
    void 자료가_없으면_빈_기간을_만들지_않는다() {
        assertThatThrownBy(
                        () ->
                                service(null)
                                        .getEvaluationWindow(
                                                query(List.of(SEMICONDUCTOR), null, 10)))
                .isInstanceOf(EvaluationWindowNotReadyException.class);
    }

    @Test
    @DisplayName("대응표가 발행되지 않았으면 그 사실을 그대로 전한다")
    void 대응표가_없으면_그_실패를_전한다() {
        EvaluationWindowQueryService service =
                new EvaluationWindowQueryService(
                        new SectorService(Optional::empty, stubProfileRepository()),
                        (mapVersion, instrumentIds) -> restrictTo(inputs(), instrumentIds),
                        new EvaluationWindowService(),
                        stubRegistry());

        assertThatThrownBy(
                        () -> service.getEvaluationWindow(query(List.of(SEMICONDUCTOR), null, 10)))
                .isInstanceOf(SectorMapNotReadyException.class);
    }

    @Test
    @DisplayName("등록되지 않은 전략은 0으로 접지 않고 거부한다")
    void 등록되지_않은_전략은_거부한다() {
        assertThatThrownBy(
                        () ->
                                service(inputs())
                                        .getEvaluationWindow(
                                                query(
                                                        List.of(SEMICONDUCTOR),
                                                        "NOT_REGISTERED",
                                                        10)))
                .hasMessageContaining("등록되지 않은 전략");
    }

    // --- 스텁 --------------------------------------------------------------

    /** 워밍업 값을 바꿔 가며 부를 수 있게, 전략 이름에 워밍업 거래일 수를 실어 보낸다. */
    private static String strategyOf(int warmupTradingDays) {
        return "WARMUP_" + warmupTradingDays;
    }

    private EvaluationWindowQueryService service(EvaluationWindowInputs inputs) {
        return new EvaluationWindowQueryService(
                new SectorService(
                        EvaluationWindowQueryServiceTest::sectorMap, stubProfileRepository()),
                (mapVersion, instrumentIds) -> restrictTo(inputs, instrumentIds),
                new EvaluationWindowService(),
                stubRegistry());
    }

    /**
     * 요청한 종목만 남긴다. 실제 저장소가 하는 일과 같다 — 걸러 주지 않으면 요청하지 않은 후보가 기준일을 정해 버려, 검사가 확인하려던 것과 다른 상황을 보게 된다.
     */
    private static Optional<EvaluationWindowInputs> restrictTo(
            EvaluationWindowInputs inputs, List<String> instrumentIds) {
        if (inputs == null) {
            return Optional.empty();
        }
        return Optional.of(
                new EvaluationWindowInputs(
                        inputs.datasetId(),
                        inputs.dataDeadline(),
                        instrumentIds.stream()
                                .map(
                                        id ->
                                                inputs.candidates().stream()
                                                        .filter(c -> c.instrumentId().equals(id))
                                                        .findFirst()
                                                        .orElseThrow())
                                .toList(),
                        inputs.tradingCalendar()));
    }

    private static EvaluationWindowQuery query(
            List<String> sectors, String strategy, Integer maxWarmup) {
        return new EvaluationWindowQuery(sectors, strategy, maxWarmup);
    }

    private static Optional<SectorListResult> sectorMap() {
        return Optional.of(
                new SectorListResult(
                        MAP_VERSION,
                        false,
                        List.of(
                                sector(SEMICONDUCTOR, SEMICONDUCTOR_ETF, LocalDate.of(2024, 1, 1)),
                                sector(BATTERY, BATTERY_ETF, LocalDate.of(2024, 3, 1)))));
    }

    private static Sector sector(String code, String instrumentId, LocalDate listedOn) {
        return new Sector(
                new SectorCode(code),
                code,
                Sector.Classification.INDUSTRY,
                new RepresentativeEtf(instrumentId, code + " ETF", listedOn, "지수", "운용사"));
    }

    private static EvaluationWindowInputs inputs() {
        return inputsWith(List.of());
    }

    private static EvaluationWindowInputs inputsWith(List<EvaluationWindowInputs.DataGap> gaps) {
        return new EvaluationWindowInputs(
                DATASET_ID,
                DEADLINE,
                List.of(
                        new EvaluationWindowInputs.Candidate(
                                SEMICONDUCTOR_ETF, LocalDate.of(2024, 1, 1), null, gaps),
                        new EvaluationWindowInputs.Candidate(
                                BATTERY_ETF, LocalDate.of(2024, 3, 1), null, List.of())),
                CALENDAR);
    }

    /**
     * 요청한 전략 이름에서 워밍업을 읽어 주는 등록표. {@code WARMUP_147}이면 147이다.
     *
     * <p>실제 등록표 파일을 읽지 않는 이유는, 여기서 확인하려는 것이 "등록표가 준 워밍업이 판정에 어떻게 반영되는가"이지 등록표 파일의 내용이 아니기 때문이다. 파일의
     * 값이 바뀔 때마다 이 테스트가 깨지면 두 가지가 한 검사에 묶인다.
     */
    private static ParameterRegistryPort stubRegistry() {
        return new ParameterRegistryPort() {

            @Override
            public String version() {
                return "TEST-1";
            }

            @Override
            public boolean provisional() {
                return false;
            }

            @Override
            public List<StrategyRegistration> activeStrategies() {
                return List.of();
            }

            @Override
            public Optional<StrategyRegistration> find(String strategyId) {
                if (MOMENTUM.equals(strategyId)) {
                    return Optional.of(registration(strategyId, 63));
                }
                if (strategyId != null && strategyId.startsWith("WARMUP_")) {
                    return Optional.of(
                            registration(
                                    strategyId,
                                    Integer.parseInt(strategyId.substring("WARMUP_".length()))));
                }
                return Optional.empty();
            }

            @Override
            public List<ValidationRule> rules(String strategyId) {
                return List.of();
            }

            @Override
            public AnalysisConditionRegistry analysisConditions() {
                throw new UnsupportedOperationException("이 테스트가 쓰지 않는다");
            }

            @Override
            public ValidationVerdict validate(
                    String strategyId, Map<String, Object> parameters, SearchContext context) {
                throw new UnsupportedOperationException("이 테스트가 쓰지 않는다");
            }

            @Override
            public Optional<String> baselineStrategyId() {
                return Optional.empty();
            }
        };
    }

    private static StrategyRegistration registration(String strategyId, int warmupTradingDays) {
        return new StrategyRegistration(
                strategyId,
                strategyId,
                null,
                List.of(
                        new ParameterAxis(
                                "lookbackDays",
                                "관측 기간",
                                null,
                                "거래일",
                                List.of(warmupTradingDays),
                                warmupTradingDays,
                                null)),
                true,
                new WarmupRule(List.of("lookbackDays")),
                "COMPUTED");
    }

    /** 이 테스트가 부르지 않는 통로. 부르면 그 자체가 결함이므로 조용한 기본값을 두지 않는다. */
    private static InstrumentProfileRepository stubProfileRepository() {
        return (mapVersion, instrumentId) -> {
            throw new UnsupportedOperationException("평가 기간 조회는 대표 상품 정보를 읽지 않는다");
        };
    }

    /** 주말만 뺀 평일 {@code count}개. */
    private static List<LocalDate> weekdays(LocalDate from, int count) {
        List<LocalDate> days = new ArrayList<>(count);
        LocalDate day = from;
        while (days.size() < count) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY
                    && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(day);
            }
            day = day.plusDays(1);
        }
        return List.copyOf(days);
    }
}
