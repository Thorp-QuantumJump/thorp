package com.ssafy.thorp.regimelink.infrastructure.backtest;

import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.port.BacktestRunReadPort;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.domain.vo.LinkRole;
import com.ssafy.thorp.result.application.service.PublicResultService;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final ParameterRegistryPort registry;

    public BacktestRunReadAdapter(
            PublicResultService publicResults,
            BacktestRunRepository runs,
            ParameterRegistryPort registry) {
        this.publicResults = publicResults;
        this.runs = runs;
        this.registry = registry;
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
        RunPlan plan = run.getPlan();
        Map<String, Object> spec = plan.requestSpecOrEmpty();
        return new BundleRunView(
                token,
                role,
                request.getId(),
                run.getId(),
                run.getStrategyId(),
                strategyName(run.getStrategyId()),
                (String) spec.get("mappingVersion"),
                universe(run, spec),
                run.getPeriod() == null ? null : run.getPeriod().startDate(),
                run.getPeriod() == null ? null : run.getPeriod().endDate(),
                plan.effectiveStart(),
                plan.warmupTradingDays() == null ? 0 : plan.warmupTradingDays(),
                run.getStatus() == RunStatus.COMPLETED);
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
