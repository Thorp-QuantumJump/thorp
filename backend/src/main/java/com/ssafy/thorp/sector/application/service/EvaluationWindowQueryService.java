package com.ssafy.thorp.sector.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.sector.application.command.EvaluationWindowQuery;
import com.ssafy.thorp.sector.application.exception.EvaluationWindowNotReadyException;
import com.ssafy.thorp.sector.application.exception.SectorNotFoundException;
import com.ssafy.thorp.sector.application.repository.EvaluationWindowRepository;
import com.ssafy.thorp.sector.application.result.EvaluationWindowInputs;
import com.ssafy.thorp.sector.application.result.EvaluationWindowResult;
import com.ssafy.thorp.sector.application.result.EvaluationWindowView;
import com.ssafy.thorp.sector.application.result.SectorListResult;
import com.ssafy.thorp.sector.application.service.EvaluationWindowService.EvaluationWindowException;
import com.ssafy.thorp.sector.domain.model.Sector;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 공통 평가 가능 기간 조회 유스케이스 (FR-007, contracts/sector-api.md).
 *
 * <p><b>산식은 {@link EvaluationWindowService}가 갖고 이 클래스는 재료를 모아 그것을 부른다.</b> 산식은 워커와 같은 픽스처로 묶여 있어
 * 한쪽만 고치면 다른 쪽 빌드가 깨지므로, 조회 경로의 사정 때문에 산식을 고치지 않는다. 여기서 하는 일은 셋이다 — 대응표에서 후보를 찾고, 저장소에서 달력과 마감 시각을
 * 읽고, 화면이 스스로 재판정할 달력 조각을 잘라 붙인다.
 *
 * <p><b>워밍업은 003 전략 등록표에서 온다.</b> 001은 이 값을 정하지 않는다(contracts/evaluation-window.md 「입력」). 요청이 전략을
 * 주면 {@link ParameterRegistryPort}로 그 전략의 <b>기본 설정</b> 워밍업을 읽고, 주지 않으면 0으로 판정한다 — 카드 여러 장을 요청 한 번으로
 * 판정하려는 화면은 전략을 비우고 {@code tradingDaysFromBase}로 직접 계산한다.
 *
 * <p><b>달력이 워밍업에 모자라도 오류가 아니다.</b> 산식은 그 경우 {@code CALENDAR_TOO_SHORT}로 실패하는데, 여기서는 그것을 {@code
 * evaluable = false}인 정상 응답으로 옮긴다. 화면이 여러 워밍업을 훑는 용도로 부르는 조회라, 훑는 도중 값 하나가 달력을 넘었다고 조회 전체가 실패하면 나머지
 * 판정까지 잃는다. <b>없는 날짜를 지어내는 것과는 다르다</b> — 날짜를 만들지 않고 "그 워밍업으로는 평가할 수 없다"고 말한다.
 */
@Service
public class EvaluationWindowQueryService {

    /** 전략을 주지 않은 요청의 워밍업. 산식은 전략 목록이 비면 거부하므로 이 이름으로 0을 넣는다. */
    private static final String NO_STRATEGY = "(none)";

    private final SectorService sectorService;
    private final EvaluationWindowRepository evaluationWindowRepository;
    private final EvaluationWindowService evaluationWindowService;
    private final ParameterRegistryPort parameterRegistry;

    public EvaluationWindowQueryService(
            SectorService sectorService,
            EvaluationWindowRepository evaluationWindowRepository,
            EvaluationWindowService evaluationWindowService,
            ParameterRegistryPort parameterRegistry) {
        this.sectorService = sectorService;
        this.evaluationWindowRepository = evaluationWindowRepository;
        this.evaluationWindowService = evaluationWindowService;
        this.parameterRegistry = parameterRegistry;
    }

    /**
     * 후보 집합의 공통 평가 가능 기간과, 화면이 다른 워밍업으로 다시 판정할 재료.
     *
     * @throws com.ssafy.thorp.sector.application.exception.SectorMapNotReadyException 대응표 미발행(409)
     * @throws SectorNotFoundException 열린 대응표에 없는 섹터 코드(404)
     * @throws EvaluationWindowNotReadyException 자료 버전·거래일 달력이 아직 없을 때(409)
     * @throws BusinessException 등록되지 않은 전략일 때(422 {@code STRATEGY_NOT_REGISTERED})
     */
    public EvaluationWindowView getEvaluationWindow(EvaluationWindowQuery query) {
        SectorListResult map = sectorService.getSectors();
        List<Sector> candidateSectors = resolveSectors(map, query.sectorCodes());
        // 종목 → 섹터. 화면이 기준 후보를 섹터 이름으로 되짚지 않게 응답에 함께 싣는다. 같은 ETF가 여러
        // 섹터의 대표일 수 있어(data-model.md) 요청에 앞서 나온 섹터를 남긴다.
        Map<String, String> sectorByInstrument = new LinkedHashMap<>();
        for (Sector sector : candidateSectors) {
            sectorByInstrument.putIfAbsent(
                    sector.representativeEtf().instrumentId(), sector.code().value());
        }
        List<String> instrumentIds = List.copyOf(sectorByInstrument.keySet());

        EvaluationWindowInputs inputs =
                evaluationWindowRepository
                        .findInputs(map.mapVersion(), instrumentIds)
                        .orElseThrow(
                                () ->
                                        new EvaluationWindowNotReadyException(
                                                "공통 평가 기간을 산출할 자료가 아직 없습니다. 자료가 적재되면 다시 조회해"
                                                        + " 주세요."));
        List<EvaluationWindowService.Candidate> candidates = toCandidates(inputs);

        // 워밍업 0 기준이 화면 재판정의 원점이다. 여기서 CALENDAR_TOO_SHORT가 나면 상장일 이후 거래일이
        // 아예 없다는 뜻이라 다른 워밍업으로도 판정할 수 없다 — 그때는 재료 부족(409)이다.
        EvaluationWindowResult base;
        try {
            base =
                    evaluationWindowService.compute(
                            candidates,
                            List.of(new EvaluationWindowService.Strategy(NO_STRATEGY, 0)),
                            inputs.dataDeadline(),
                            inputs.tradingCalendar());
        } catch (EvaluationWindowException e) {
            if ("CALENDAR_TOO_SHORT".equals(e.code())) {
                throw new EvaluationWindowNotReadyException(
                        "후보 가운데 상장일 이후의 거래일이 아직 없는 종목이 있습니다. 자료가 적재되면 다시 조회해 주세요.");
            }
            throw e;
        }

        int warmup = resolveWarmup(query.strategyId());
        EvaluationWindowResult applied = applyWarmup(candidates, inputs, warmup, base);

        List<LocalDate> fromBase =
                query.maxWarmupTradingDays() == null
                        ? null
                        : tradingDaysFromBase(
                                inputs.tradingCalendar(),
                                base.commonStart(),
                                base.commonEnd(),
                                query.maxWarmupTradingDays());

        return new EvaluationWindowView(
                applied == null ? null : applied.commonStart(),
                base.commonEnd(),
                warmup,
                base.bindingCandidate(),
                sectorByInstrument.get(base.bindingCandidate()),
                applied == null ? List.of() : applied.excludedRanges(),
                applied != null && applied.evaluable(),
                inputs.datasetId(),
                base.commonStart(),
                fromBase,
                fromBase == null || fromBase.isEmpty() ? null : fromBase.size() - 1,
                fromBase == null ? null : base.excludedRanges());
    }

    /** 요청한 순서를 그대로 지킨다. 산식의 {@code bindingCandidate} 동점 규칙이 입력 순서를 쓰기 때문이다. */
    private static List<Sector> resolveSectors(SectorListResult map, List<String> sectorCodes) {
        List<Sector> resolved = new ArrayList<>(sectorCodes.size());
        for (String code : sectorCodes) {
            resolved.add(
                    map.sectors().stream()
                            .filter(sector -> sector.code().value().equals(code))
                            .findFirst()
                            .orElseThrow(() -> new SectorNotFoundException(code)));
        }
        return resolved;
    }

    private static List<EvaluationWindowService.Candidate> toCandidates(
            EvaluationWindowInputs inputs) {
        return inputs.candidates().stream()
                .map(
                        candidate ->
                                new EvaluationWindowService.Candidate(
                                        candidate.instrumentId(),
                                        candidate.listedOn(),
                                        candidate.delistedOn(),
                                        candidate.dataGaps().stream()
                                                .map(
                                                        gap ->
                                                                new EvaluationWindowService.DataGap(
                                                                        gap.start(),
                                                                        gap.end(),
                                                                        gap.reason()))
                                                .toList()))
                .toList();
    }

    /**
     * 전략의 <b>기본 설정</b> 워밍업. 전략을 주지 않은 요청이면 0이다.
     *
     * <p>기본 설정을 쓰는 이유는 화면이 상세 설정을 연 상태를 001에 알리지 않기 때문이다. 사용자가 값을 바꾼 뒤의 판정은 화면이 {@code
     * tradingDaysFromBase}로 직접 한다 — 그것이 이 조회에 달력 조각을 실어 보내는 목적이다.
     */
    private int resolveWarmup(String strategyId) {
        if (strategyId == null) {
            return 0;
        }
        StrategyRegistration registration =
                parameterRegistry
                        .find(strategyId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.STRATEGY_NOT_REGISTERED,
                                                "등록되지 않은 전략입니다: " + strategyId));
        if (registration.warmup() == null) {
            // 등록표가 규칙을 적지 않은 전략은 워밍업 0이다(003 data-model §6).
            return 0;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (ParameterAxis axis : registration.axes()) {
            if (axis.defaultValue() != null) {
                defaults.put(axis.name(), axis.defaultValue());
            }
        }
        try {
            return registration.warmup().evaluate(defaults);
        } catch (IllegalArgumentException e) {
            // 등록표가 워밍업에 쓰는 축의 기본값을 비워 둔 상태다. 0으로 접으면 평가 구간이 실제보다 길게
            // 잡히므로(원칙 VI) 값을 지어내지 않고 조회를 실패시킨다.
            throw new BusinessException(
                    ErrorCode.REGISTRY_UNAVAILABLE, "전략의 워밍업을 등록표에서 읽을 수 없습니다: " + strategyId, e);
        }
    }

    /**
     * 워밍업을 반영한 결과. 달력이 그만큼에 모자라면 {@code null}이고 호출자가 {@code evaluable = false}로 옮긴다.
     *
     * <p>워밍업이 0이면 이미 구해 둔 기준 결과를 그대로 쓴다 — 같은 입력으로 산식을 두 번 돌릴 이유가 없다.
     */
    private EvaluationWindowResult applyWarmup(
            List<EvaluationWindowService.Candidate> candidates,
            EvaluationWindowInputs inputs,
            int warmup,
            EvaluationWindowResult base) {
        if (warmup == 0) {
            return base;
        }
        try {
            return evaluationWindowService.compute(
                    candidates,
                    List.of(new EvaluationWindowService.Strategy(NO_STRATEGY, warmup)),
                    inputs.dataDeadline(),
                    inputs.tradingCalendar());
        } catch (EvaluationWindowException e) {
            if ("CALENDAR_TOO_SHORT".equals(e.code())) {
                return null;
            }
            throw e;
        }
    }

    /**
     * {@code warmupBaseDate}부터 {@code commonEnd}까지의 거래일. 길이는 {@code maxWarmup + 1}을 넘지 않는다.
     *
     * <p>{@code commonEnd}에서 끊는 이유는 그 뒤의 날짜가 평가 구간이 될 수 없기 때문이다 — 자료 마감이나 상장폐지 뒤의 날짜를 목록에 넣으면 화면이
     * 그것을 시작일로 계산해 낼 수 있다.
     *
     * <p>빈 목록은 {@code warmupBaseDate}가 이미 {@code commonEnd}를 넘었다는 뜻이며, 그 조합은 어떤 워밍업으로도 평가할 수 없다.
     */
    private static List<LocalDate> tradingDaysFromBase(
            List<LocalDate> calendar, LocalDate base, LocalDate end, int maxWarmup) {
        List<LocalDate> days = new ArrayList<>(Math.min(maxWarmup + 1, calendar.size()));
        for (LocalDate day : calendar) {
            if (day.isBefore(base)) {
                continue;
            }
            if (day.isAfter(end) || days.size() > maxWarmup) {
                break;
            }
            days.add(day);
        }
        return List.copyOf(days);
    }
}
