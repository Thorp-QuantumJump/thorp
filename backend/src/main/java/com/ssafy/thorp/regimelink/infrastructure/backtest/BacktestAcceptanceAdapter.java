package com.ssafy.thorp.regimelink.infrastructure.backtest;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.service.BacktestRetryService;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.port.BacktestAcceptancePort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 011이 사용자 이름으로 004 파생 실행을 접수한다 (T058·T067·T069).
 *
 * <p><b>004 접수 규칙을 다시 쓰지 않는다.</b> 조건은 기준 실행의 요청 사본({@code RunPlan.requestSpec})을 그대로 베끼고 기간과 시나리오만
 * 바꾼다 — 여기서 전략 설정을 새로 만들면 비교 묶음의 두 실행이 사실은 다른 조건이 된다.
 *
 * <p>004 의 동일 버전 재사용이 살아 있으므로 같은 조건의 실행이 이미 있으면 새 계산 없이 그 실행에 합류하거나 결과를 재사용한다. 그래도 <b>요청은 호출자마다 새로
 * 생긴다</b> — 그래서 두 사용자가 같은 사례를 켜도 실행 하나에 분석 요청 둘이다.
 */
@Component
public class BacktestAcceptanceAdapter implements BacktestAcceptancePort {

    private final BacktestService backtests;
    private final BacktestRetryService retries;
    private final BacktestRunRepository runs;
    private final BacktestRequestRepository requests;

    public BacktestAcceptanceAdapter(
            BacktestService backtests,
            BacktestRetryService retries,
            BacktestRunRepository runs,
            BacktestRequestRepository requests) {
        this.backtests = backtests;
        this.retries = retries;
        this.runs = runs;
        this.requests = requests;
    }

    @Override
    public AcceptedRun acceptFullPeriod(
            Long originRunId, long requesterId, LocalDate start, LocalDate end) {
        return accept(copy(originRunId, requesterId, start, end, Scenario.FULL_PERIOD, null));
    }

    @Override
    public AcceptedRun acceptFreshCash(
            Long originRunId, long requesterId, LocalDate start, LocalDate d0, LocalDate end) {
        return accept(
                copy(
                        originRunId,
                        requesterId,
                        start,
                        end,
                        Scenario.FRESH_CASH_FROM,
                        Map.of("startDate", d0.toString())));
    }

    @Override
    public AcceptedRun retry(Long runId, Long requestId, long requesterId) {
        BacktestRequest request =
                requests.findByRunId(runId).stream()
                        .filter(found -> found.getId().equals(requestId))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return accept(retries.retry(request, requesterId));
    }

    private AcceptedRun accept(CreateBacktestCommand command) {
        return accept(backtests.create(command));
    }

    /**
     * 접수 결과를 실행 PK 로 바꾼다.
     *
     * <p>004 가 돌려주는 {@code backtestId}는 <b>요청</b>의 것이고 링크가 기다리는 것은 <b>실행</b>이라 한 번 더 읽는다(요청과 실행은
     * 다대일이다).
     */
    private AcceptedRun accept(BacktestJobResult accepted) {
        BacktestRequest request =
                requests.findByBacktestId(accepted.backtestId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        BacktestRun run =
                runs.findById(request.getRunId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return new AcceptedRun(
                run.getId(), request.getId(), run.getStatus() == RunStatus.COMPLETED);
    }

    /**
     * 기준 실행의 조건 사본. 기간과 시나리오만 바꾼다.
     *
     * <p>{@code registryVersion}·{@code evaluationSplitVersion}처럼 접수 기록에만 있는 값은 그 기록에서 읽는다 — 없으면 없는
     * 대로 보낸다. 지금 값으로 채우면 기준 실행이 쓰지 않은 등록표로 판정하게 된다.
     */
    private CreateBacktestCommand copy(
            Long originRunId,
            long requesterId,
            LocalDate start,
            LocalDate end,
            Scenario scenario,
            Map<String, Object> scenarioParams) {
        BacktestRun origin =
                runs.findById(originRunId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        RunPlan plan = origin.getPlan();
        Map<String, Object> spec = plan.requestSpecOrEmpty();
        return new CreateBacktestCommand(
                requesterId,
                ExecutionType.SINGLE,
                text(spec, "mappingVersion"),
                items(spec),
                origin.getStrategyId(),
                plan.rulesVersion(),
                text(spec, "registryVersion"),
                origin.getParameters(),
                start,
                end,
                origin.getInitialCash(),
                plan.costProfileVersion(),
                text(spec, "evaluationSplitVersion"),
                plan.warmupTradingDays(),
                scenario,
                scenarioParams);
    }

    private static List<CreateBacktestCommand.AllocationItem> items(Map<String, Object> spec) {
        List<CreateBacktestCommand.AllocationItem> items = new ArrayList<>();
        if (spec.get("items") instanceof List<?> raw) {
            for (Object element : raw) {
                if (element instanceof Map<?, ?> item
                        && item.get("instrumentId") instanceof String instrumentId) {
                    items.add(
                            new CreateBacktestCommand.AllocationItem(
                                    item.get("sectorId") instanceof String sectorId
                                            ? sectorId
                                            : null,
                                    instrumentId,
                                    weight(item.get("targetWeight"))));
                }
            }
        }
        if (items.isEmpty()) {
            // 접수 기록이 없는 실행은 조건을 베낄 수 없다. 종목만으로 요청을 지어내면 그 실행이
            // 고르지 않은 섹터·비중이 새 실행에 들어간다.
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이 분석은 다시 계산할 접수 기록이 남아 있지 않습니다.");
        }
        return items;
    }

    private static BigDecimal weight(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private static String text(Map<String, Object> spec, String key) {
        return spec.get(key) instanceof String value ? value : null;
    }
}
