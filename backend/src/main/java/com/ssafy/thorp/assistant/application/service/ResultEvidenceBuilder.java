package com.ssafy.thorp.assistant.application.service;

import com.ssafy.thorp.assistant.application.port.AssistantSettings;
import com.ssafy.thorp.assistant.application.port.AssistantTool;
import com.ssafy.thorp.assistant.application.port.EvidenceBuilder;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceBundle;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.PageContext;
import com.ssafy.thorp.assistant.application.result.PageKind;
import com.ssafy.thorp.assistant.application.service.tools.DeepestDrawdownWindowTool;
import com.ssafy.thorp.assistant.application.service.tools.EquityOnTool;
import com.ssafy.thorp.assistant.application.service.tools.EquityRangeTool;
import com.ssafy.thorp.assistant.application.service.tools.PositionsOnTool;
import com.ssafy.thorp.assistant.application.service.tools.ResultToolContext;
import com.ssafy.thorp.assistant.application.service.tools.TradesBetweenTool;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.application.result.BacktestWeightsResult;
import com.ssafy.thorp.result.application.result.PublicResultDetail;
import com.ssafy.thorp.result.application.service.PublicResultService;
import com.ssafy.thorp.sector.application.service.SectorService;
import com.ssafy.thorp.sector.domain.model.Sector;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 결과 화면의 근거 팩(FR-006, 계약 §2.1 포함 표)과 도구 다섯 개(FR-007).
 *
 * <p>초기 조회는 {@code getWeights} → {@code getDetail} 두 번이다. {@code getWeights}가 005 {@code
 * requireResultReady}의 409(계산 중·실패·시작 전 취소)를 그대로 던지므로 결과 없음 판정을 따로 만들지 않는다. 결과 타입 record 를 통째로 옮기지
 * 않고 표의 항목만 필드 단위로 옮긴다 — {@code publicToken}·{@code expiresAt}·{@code versions}·{@code progress}·상관
 * 행렬은 넣지 않는다.
 *
 * <p>팩 상한을 넘으면 섹터별 위험·손실 기여부터 빼고 "일부 분석 값은 싣지 않음" 근거 하나를 남긴다. 목표·실제 비중은 항상 싣는다.
 */
@Component
public class ResultEvidenceBuilder implements EvidenceBuilder {
    static final String TRIMMED_LABEL = "분석 값 읽은 범위";
    static final String TRIMMED_VALUE = "일부 분석 값은 싣지 않음";
    private static final Logger log = LoggerFactory.getLogger(ResultEvidenceBuilder.class);
    private static final String SUMMARY = "summary";
    private static final String ANALYSIS = "analysis";
    private static final String SETTINGS = "settings";

    private final PublicResultService publicResults;
    private final SectorService sectors;
    private final ParameterRegistryPort registry;
    private final AssistantSettings settings;
    private final int tradesPageSize;

    public ResultEvidenceBuilder(
            PublicResultService publicResults,
            SectorService sectors,
            ParameterRegistryPort registry,
            AssistantSettings settings,
            @Value("${thorp.result.trades-page-size}") int tradesPageSize) {
        this.publicResults = publicResults;
        this.sectors = sectors;
        this.registry = registry;
        this.settings = settings;
        this.tradesPageSize = tradesPageSize;
    }

    @Override
    public boolean supports(PageKind kind) {
        return kind == PageKind.RESULT;
    }

    @Override
    public EvidenceBundle build(PageContext context) {
        String token = context.publicToken();
        BacktestWeightsResult weights = publicResults.getWeights(token);
        PublicResultDetail detail = publicResults.getDetail(token);
        Map<String, Object> config = detail.config() == null ? Map.of() : detail.config();
        Map<String, String> sectorNames = sectorNames();
        List<Item> items = items(config, sectorNames);
        Map<String, String> labels = new LinkedHashMap<>();
        for (Item item : items) {
            labels.put(item.instrumentId(), item.name());
        }

        Facts fixed = new Facts();
        Facts perSector = new Facts();
        Facts contributions = new Facts();
        BacktestDetailResult.Summary summary = detail.summary();
        summaryFacts(summary, fixed);
        configFacts(config, detail, fixed);
        for (Item item : items) {
            perSector.add(
                    item.name() + " 대표 ETF",
                    item.instrumentId(),
                    null,
                    SETTINGS,
                    EvidenceKind.TEXT);
            perSector.add(
                    item.name() + " 목표 비중",
                    item.targetWeight(),
                    null,
                    SETTINGS,
                    EvidenceKind.RATIO);
        }
        weightsFacts(weights, detail, labels, fixed, perSector);
        if (summary != null && summary.analytics() != null) {
            contributionFacts(summary.analytics(), labels, contributions);
        }
        if (detail.exceptionKinds() != null && !detail.exceptionKinds().isEmpty()) {
            fixed.add(
                    "계산 중 예외",
                    String.join("·", detail.exceptionKinds()),
                    null,
                    ANALYSIS,
                    EvidenceKind.TEXT);
        }
        LocalDate partialUntil = null;
        if (detail.partial()) {
            partialUntil = lastTimeseriesDate(token);
            fixed.add("결과 상태", "완료 끝 날짜까지의 부분 결과", null, SUMMARY, EvidenceKind.TEXT);
            if (partialUntil != null) {
                fixed.add(
                        "완료 끝 날짜",
                        partialUntil.toString(),
                        partialUntil,
                        SUMMARY,
                        EvidenceKind.DATE);
            }
        }

        List<Evidence> all = new ArrayList<>(fixed.list);
        all.addAll(perSector.list);
        if (all.size() + contributions.list.size() <= settings.maxFacts()) {
            all.addAll(contributions.list);
        } else if (!contributions.list.isEmpty()) {
            all.add(
                    new Evidence(
                            null, TRIMMED_LABEL, TRIMMED_VALUE, null, ANALYSIS, EvidenceKind.TEXT));
            log.info(
                    "assistant evidence trimmed: sectors={} fixed={} contributions dropped={}",
                    items.size(),
                    fixed.list.size(),
                    contributions.list.size());
        }

        ResultToolContext toolContext =
                new ResultToolContext(
                        publicResults,
                        token,
                        date(config, "effectiveStart", "start", LocalDate.MIN),
                        // 부분 결과는 완료 끝 날짜까지만 읽는다 — 계획된 종료일까지 열어 두면 미계산 날짜를 휴장일로 설명한다.
                        partialUntil != null
                                ? partialUntil
                                : date(config, "effectiveEnd", "end", LocalDate.MAX),
                        labels,
                        summary == null ? null : summary.maxDrawdown(),
                        tradesPageSize);
        List<AssistantTool> tools =
                List.of(
                        new EquityOnTool(toolContext),
                        new EquityRangeTool(toolContext),
                        new TradesBetweenTool(toolContext),
                        new PositionsOnTool(toolContext),
                        new DeepestDrawdownWindowTool(toolContext));
        return new EvidenceBundle(all, tools, partialUntil);
    }

    // ---- 포함 표

    private static void summaryFacts(BacktestDetailResult.Summary summary, Facts facts) {
        if (summary == null) {
            return;
        }
        facts.add("시작 자산", summary.initialEquity(), null, SUMMARY, EvidenceKind.MONEY);
        facts.add("종료 자산", summary.finalEquity(), null, SUMMARY, EvidenceKind.MONEY);
        facts.add("전체 수익률", summary.totalReturn(), null, SUMMARY, EvidenceKind.RATIO);
        facts.add("비용 차감 후 전체 수익률", summary.netTotalReturn(), null, SUMMARY, EvidenceKind.RATIO);
        facts.add("연복리 수익률", summary.cagr(), null, SUMMARY, EvidenceKind.RATIO);
        facts.add("가장 크게 줄었던 폭", summary.maxDrawdown(), null, SUMMARY, EvidenceKind.RATIO);
        facts.add("변동성", summary.volatility(), null, SUMMARY, EvidenceKind.RATIO);
        facts.add("승률", summary.winRate(), null, SUMMARY, EvidenceKind.RATIO);
        facts.add("샤프 비율", summary.sharpeRatio(), null, SUMMARY, EvidenceKind.DECIMAL2);
        facts.add(
                "거래 횟수", String.valueOf(summary.tradeCount()), null, SUMMARY, EvidenceKind.INTEGER);
        facts.add("거래비용 합계", summary.totalTradingCost(), null, SETTINGS, EvidenceKind.MONEY);
        EngineOutput.Costs costs = summary.costs();
        if (costs != null) {
            facts.add("수수료", costs.commission(), null, SETTINGS, EvidenceKind.MONEY);
            facts.add("스프레드", costs.spread(), null, SETTINGS, EvidenceKind.MONEY);
            facts.add("슬리피지", costs.slippage(), null, SETTINGS, EvidenceKind.MONEY);
            facts.add("세금", costs.tax(), null, SETTINGS, EvidenceKind.MONEY);
        }
        EngineOutput.Analytics analytics = summary.analytics();
        if (analytics != null) {
            facts.add(
                    "회복 기간(거래일)",
                    analytics.recoveryDays() == null
                            ? null
                            : String.valueOf(analytics.recoveryDays()),
                    null,
                    ANALYSIS,
                    EvidenceKind.INTEGER);
            facts.add("회전율", analytics.turnover(), null, ANALYSIS, EvidenceKind.RATIO);
            facts.add("비중 오차", analytics.weightError(), null, ANALYSIS, EvidenceKind.RATIO);
            facts.add("추정 방법", analytics.method(), null, ANALYSIS, EvidenceKind.TEXT);
        }
    }

    private void configFacts(Map<String, Object> config, PublicResultDetail detail, Facts facts) {
        facts.add("요청 시작일", text(config.get("start")), null, SETTINGS, EvidenceKind.DATE);
        facts.add("요청 종료일", text(config.get("end")), null, SETTINGS, EvidenceKind.DATE);
        facts.add("평가 시작일", text(config.get("effectiveStart")), null, SETTINGS, EvidenceKind.DATE);
        facts.add("평가 종료일", text(config.get("effectiveEnd")), null, SETTINGS, EvidenceKind.DATE);
        facts.add(
                "준비 기간(거래일)",
                text(config.get("warmupTradingDays")),
                null,
                SETTINGS,
                EvidenceKind.INTEGER);
        facts.add("시작 금액", text(config.get("initialCash")), null, SETTINGS, EvidenceKind.MONEY);
        String strategyId = text(config.get("strategyId"));
        String strategyName = detail.strategyName() == null ? strategyId : detail.strategyName();
        facts.add("전략", strategyName, null, SETTINGS, EvidenceKind.TEXT);
        Map<String, Object> parameters = mapOf(config.get("parameters"));
        Map<String, String> axisLabels = axisLabels(strategyId, parameters);
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            String label = axisLabels.getOrDefault(entry.getKey(), entry.getKey());
            facts.add(label, text(entry.getValue()), null, SETTINGS, EvidenceKind.TEXT);
        }
    }

    private static void weightsFacts(
            BacktestWeightsResult weights,
            PublicResultDetail detail,
            Map<String, String> labels,
            Facts fixed,
            Facts perSector) {
        WeightsSource source =
                detail.weightsSource() != null ? detail.weightsSource() : weights.weightsSource();
        if (source != null) {
            fixed.add(
                    "비중 출처",
                    source == WeightsSource.USER ? "사용자 입력" : "전략 계산",
                    null,
                    SETTINGS,
                    EvidenceKind.TEXT);
        }
        List<EngineOutput.WeightsPoint> points = weights.points();
        if (points == null || points.isEmpty()) {
            return;
        }
        EngineOutput.WeightsPoint last = points.get(points.size() - 1);
        if (last.actual() == null) {
            return;
        }
        for (Map.Entry<String, BigDecimal> entry : last.actual().entrySet()) {
            perSector.add(
                    name(labels, entry.getKey()) + " 실제 비중",
                    entry.getValue(),
                    last.date(),
                    SETTINGS,
                    EvidenceKind.RATIO);
        }
    }

    private static void contributionFacts(
            EngineOutput.Analytics analytics, Map<String, String> labels, Facts facts) {
        if (analytics.riskContribution() != null) {
            for (Map.Entry<String, BigDecimal> entry : analytics.riskContribution().entrySet()) {
                facts.add(
                        name(labels, entry.getKey()) + " 위험 기여",
                        entry.getValue(),
                        null,
                        ANALYSIS,
                        EvidenceKind.RATIO);
            }
        }
        if (analytics.lossContribution() != null) {
            for (Map.Entry<String, BigDecimal> entry : analytics.lossContribution().entrySet()) {
                facts.add(
                        name(labels, entry.getKey()) + " 손실 기여",
                        entry.getValue(),
                        null,
                        ANALYSIS,
                        EvidenceKind.RATIO);
            }
        }
    }

    // ---- 이름 원천

    /** 001 섹터 목록. 못 읽으면 빈 표 — 라벨은 id 원문으로 간다(지어내지 않는다). */
    private Map<String, String> sectorNames() {
        try {
            Map<String, String> names = new LinkedHashMap<>();
            for (Sector sector : sectors.getSectors().sectors()) {
                names.put(sector.code().value(), sector.name());
            }
            return names;
        } catch (RuntimeException exc) {
            log.info(
                    "assistant evidence: sector names unavailable ({})",
                    exc.getClass().getSimpleName());
            return Map.of();
        }
    }

    /** 003 등록표의 축 라벨. 등록표를 못 읽거나 전략이 없으면 축 이름 그대로 쓴다. */
    private Map<String, String> axisLabels(String strategyId, Map<String, Object> parameters) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (strategyId == null) {
            return labels;
        }
        try {
            registry.find(strategyId)
                    .ifPresent(
                            registration -> {
                                for (ParameterAxis axis : registration.applicableAxes(parameters)) {
                                    String unit =
                                            axis.unit() == null || axis.unit().isBlank()
                                                    ? ""
                                                    : " (" + axis.unit() + ")";
                                    labels.put(axis.name(), axis.label() + unit);
                                }
                            });
        } catch (RuntimeException exc) {
            log.info(
                    "assistant evidence: axis labels unavailable ({})",
                    exc.getClass().getSimpleName());
        }
        return labels;
    }

    private static List<Item> items(Map<String, Object> config, Map<String, String> sectorNames) {
        List<Item> items = new ArrayList<>();
        if (config.get("items") instanceof List<?> rows) {
            for (Object row : rows) {
                Map<String, Object> item = mapOf(row);
                String sectorId = text(item.get("sectorId"));
                String instrumentId = text(item.get("instrumentId"));
                if (instrumentId == null) {
                    continue;
                }
                String name =
                        sectorId == null
                                ? instrumentId
                                : sectorNames.getOrDefault(sectorId, sectorId);
                items.add(new Item(sectorId, instrumentId, text(item.get("targetWeight")), name));
            }
        }
        return items;
    }

    private static String name(Map<String, String> labels, String instrumentId) {
        String name = labels.get(instrumentId);
        return name == null ? instrumentId : name;
    }

    private LocalDate lastTimeseriesDate(String token) {
        List<BacktestTimeseriesResult.Point> points = publicResults.getTimeseries(token).points();
        return points == null || points.isEmpty() ? null : points.get(points.size() - 1).date();
    }

    private static LocalDate date(
            Map<String, Object> config, String key, String fallbackKey, LocalDate absent) {
        String raw =
                Optional.ofNullable(text(config.get(key))).orElse(text(config.get(fallbackKey)));
        if (raw == null) {
            return absent;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException exc) {
            return absent;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }

    private record Item(String sectorId, String instrumentId, String targetWeight, String name) {}

    /** 값이 비어 있으면 근거를 만들지 않는다 — 없는 값에 자리를 주지 않는다. */
    private static final class Facts {
        final List<Evidence> list = new ArrayList<>();

        void add(String label, BigDecimal value, LocalDate asOf, String tab, EvidenceKind kind) {
            add(label, value == null ? null : value.toPlainString(), asOf, tab, kind);
        }

        void add(String label, String value, LocalDate asOf, String tab, EvidenceKind kind) {
            if (value == null || value.isBlank()) {
                return;
            }
            list.add(new Evidence(null, label, value, asOf, tab, kind));
        }
    }
}
