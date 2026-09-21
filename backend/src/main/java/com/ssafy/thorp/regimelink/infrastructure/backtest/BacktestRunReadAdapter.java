package com.ssafy.thorp.regimelink.infrastructure.backtest;

import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.RunLifecycle;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.model.RunProgress;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.port.BacktestRunReadPort;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.port.RunProgressView;
import com.ssafy.thorp.regimelink.application.port.RunSlice;
import com.ssafy.thorp.regimelink.domain.vo.LinkRole;
import com.ssafy.thorp.result.application.service.PublicResultService;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 토큰을 005 규칙으로 풀어 004 실행을 읽는다 (research R5, T026).
 *
 * <p><b>토큰 판정을 011이 다시 쓰지 않는다.</b> 형식(400)·존재(404)·만료(410)는 {@link PublicResultService#resolve}가
 * 소유하고, 여기서는 그 결과인 분석 요청을 받아 실행으로 내려간다. 같은 거절을 두 경로가 다른 코드로 말하면 화면이 두 벌로 분기한다.
 *
 * <p><b>저장 결과에서 증명되지 않은 값은 추정하지 않는다.</b> 워밍업·평가 시작일은 실행 계획({@link RunPlan})에 고정된 값을 그대로 읽고, 없으면 없는
 * 대로 판정에 반영한다 — 기본값을 채우면 그 실행이 실제로 덮지 않은 구간을 덮는 것처럼 보인다.
 */
@Component
public class BacktestRunReadAdapter implements BacktestRunReadPort {

    private final PublicResultService publicResults;
    private final BacktestRunRepository runs;
    private final BacktestRequestRepository requests;
    private final BacktestResultRepository results;
    private final ParameterRegistryPort registry;
    private final Clock clock;

    public BacktestRunReadAdapter(
            PublicResultService publicResults,
            BacktestRunRepository runs,
            BacktestRequestRepository requests,
            BacktestResultRepository results,
            ParameterRegistryPort registry,
            Clock clock) {
        this.publicResults = publicResults;
        this.runs = runs;
        this.requests = requests;
        this.results = results;
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BundleRunView> resolveBundleByRequest(
            Long baseRunId, Long baseRequestId, Long compareRunId, Long compareRequestId) {
        List<BundleRunView> bundle = new ArrayList<>(2);
        bundle.add(byRequest(baseRunId, baseRequestId, LinkRole.BASE));
        if (compareRunId != null) {
            bundle.add(byRequest(compareRunId, compareRequestId, LinkRole.COMPARE));
        }
        return List.copyOf(bundle);
    }

    private BundleRunView byRequest(Long runId, Long requestId, LinkRole role) {
        BacktestRun run =
                runs.findById(runId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        BacktestRequest request =
                requestId == null
                        ? null
                        : requests.findByRunId(runId).stream()
                                .filter(found -> requestId.equals(found.getId()))
                                .findFirst()
                                .orElse(null);
        String token =
                request == null || request.getPublicToken() == null
                        ? null
                        : request.getPublicToken().value();
        return view(token, role, requestId, run);
    }

    /**
     * 실행 PK 로 읽는다. 링크가 들고 있는 것이 PK 라 조회·감시가 이 경로를 쓴다.
     *
     * <p>{@code publicToken}·{@code role}·{@code requestId} 는 <b>토큰에서 나오는 값</b>이라 여기서는 알 수 없다. 자리
     * 표시로 채우지 않는다 — 응답에 실리는 토큰은 언제나 요청자가 제시한 것이어야 한다(research R14).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<BundleRunView> findRun(Long runId) {
        return runs.findById(runId).map(run -> view(null, LinkRole.BASE, null, run));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunSlice> readSlice(
            Long runId, LocalDate baselineDate, LocalDate d0, LocalDate lastAvailable) {
        return runs.findById(runId)
                .filter(run -> run.getStatus() == RunStatus.COMPLETED)
                .flatMap(
                        run ->
                                BacktestRunSliceReader.read(
                                        run, results, baselineDate, d0, lastAvailable));
    }

    /**
     * 004 진행 상태를 그대로 옮긴다 (contracts §3 {@code runProgress}).
     *
     * <p>004 조회 서비스를 부르지 않는 것은 그 경로가 <b>요청</b> 단위이고 요청자 확인을 함께 하기 때문이다. 링크는 여러 사용자가 공유해 어느 요청으로 물을지
     * 정할 수 없으므로 실행 행을 직접 읽는다 — 실려 나가는 값은 상태·진행률·대기 시간뿐이라 남의 결과가 새지 않는다.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<RunProgressView> readProgress(Long runId) {
        return runs.findById(runId).map(this::progress);
    }

    private RunProgressView progress(BacktestRun run) {
        RunProgress found = run.getProgress();
        RunLifecycle lifecycle = run.getLifecycle();
        return new RunProgressView(
                run.getStatus() == null ? null : run.getStatus().name(),
                found == null
                        ? null
                        : new RunProgressView.Progress(
                                found.stage() == null ? null : found.stage().name(),
                                found.lastTradingDate(),
                                found.completedTradingDays(),
                                found.totalTradingDays()),
                queueWaitSeconds(lifecycle));
    }

    /** 큐에서 기다린 시간. 아직 시작되지 않았으면 지금까지다(004 조회와 같은 셈). */
    private Long queueWaitSeconds(RunLifecycle lifecycle) {
        if (lifecycle == null || lifecycle.queuedAt() == null) {
            return null;
        }
        Instant until = lifecycle.startedAt() == null ? Instant.now(clock) : lifecycle.startedAt();
        return Math.max(0L, Duration.between(lifecycle.queuedAt(), until).toSeconds());
    }

    @Override
    @Transactional(readOnly = true)
    public List<BundleRunView> resolveBundle(String baseToken, String compareToken) {
        List<BundleRunView> bundle = new ArrayList<>(2);
        bundle.add(resolve(baseToken, LinkRole.BASE));
        if (compareToken != null && !compareToken.isBlank()) {
            bundle.add(resolve(compareToken, LinkRole.COMPARE));
        }
        return List.copyOf(bundle);
    }

    private BundleRunView resolve(String token, LinkRole role) {
        BacktestRequest request = publicResults.resolve(token);
        BacktestRun run =
                runs.findById(request.getRunId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return view(token, role, request.getId(), run);
    }

    private BundleRunView view(String token, LinkRole role, Long requestId, BacktestRun run) {
        RunPlan plan = run.getPlan();
        Map<String, Object> spec = plan.requestSpecOrEmpty();
        return new BundleRunView(
                token,
                role,
                requestId,
                run.getId(),
                run.getStrategyId(),
                strategyName(run.getStrategyId()),
                (String) spec.get("mappingVersion"),
                run.getInitialCash(),
                plan.costProfileVersion(),
                plan.dataVersion(),
                universe(run, spec),
                run.getPeriod() == null ? null : run.getPeriod().startDate(),
                run.getPeriod() == null ? null : run.getPeriod().endDate(),
                plan.effectiveStart(),
                resultEnd(run, plan),
                plan.warmupTradingDays() == null ? 0 : plan.warmupTradingDays(),
                run.getStatus() == RunStatus.COMPLETED);
    }

    /**
     * 이 실행의 결과가 실제로 닿는 마지막 거래일.
     *
     * <p>취소된 실행은 요청한 기간을 다 돌지 않았으므로 마지막으로 계산한 거래일까지다(004 R-04). 한 거래일도 돌지 않고 취소됐으면 결과가 없어 {@code
     * null}이다 — 그런 실행은 어느 사후 구간도 덮지 않는다.
     *
     * <p>아직 도는 실행은 계획한 끝을 쓴다. 기간 판정이 묻는 것은 "요청한 구간이 사례를 담는가"이고, 결과를 기다리는 일은 링크의 {@code PENDING_RUN}
     * 이 맡는다.
     */
    private static LocalDate resultEnd(BacktestRun run, RunPlan plan) {
        if (run.getStatus() == RunStatus.CANCELLED) {
            return run.getProgress() == null ? null : run.getProgress().lastTradingDate();
        }
        if (plan.effectiveEnd() != null) {
            return plan.effectiveEnd();
        }
        return run.getPeriod() == null ? null : run.getPeriod().endDate();
    }

    private String strategyName(String strategyId) {
        if (strategyId == null) {
            return null;
        }
        return registry.find(strategyId).map(StrategyRegistration::name).orElse(null);
    }

    /**
     * 실행이 고른 (섹터, 종목) 쌍.
     *
     * <p>접수 경로가 {@code requestSpec.items} 에 그 쌍을 남긴다. 기록이 없는 실행(접수 경로를 거치지 않은 행)은 종목만 알 수 있으므로 섹터를
     * 비워 둔다 — 종목에서 섹터를 거꾸로 찾아 채우면 그 실행이 실제로 고르지 않은 섹터를 판정에 넣게 된다.
     */
    private static List<BundleRunView.UniverseEntry> universe(
            BacktestRun run, Map<String, Object> spec) {
        Set<BundleRunView.UniverseEntry> found = new LinkedHashSet<>();
        if (spec.get("items") instanceof List<?> items) {
            for (Object element : items) {
                if (element instanceof Map<?, ?> item
                        && item.get("instrumentId") instanceof String instrumentId) {
                    found.add(
                            new BundleRunView.UniverseEntry(
                                    item.get("sectorId") instanceof String sectorId
                                            ? sectorId
                                            : null,
                                    instrumentId));
                }
            }
        }
        if (found.isEmpty()) {
            run.getInstrumentIds()
                    .forEach(id -> found.add(new BundleRunView.UniverseEntry(null, id.value())));
        }
        return List.copyOf(found);
    }
}
