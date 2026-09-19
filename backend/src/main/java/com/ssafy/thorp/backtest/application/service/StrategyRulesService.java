package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.repository.StrategyRulesRepository;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전략 규칙 등록표 조회(spec 004 research R-08, T029).
 *
 * <p>접수 단계에서 이 서비스를 통과하지 못한 요청은 실행을 만들지 않는다. 워커가 규칙 명세 없이 계산하는 일을 막는 마지막 지점이다 — 워커는 작업 메시지에 실려 온
 * 명세만 신뢰하고 자체 기본값을 두지 않으므로, 명세 없는 요청을 통과시키면 실행이 만들어진 뒤 엔진에서 실패한다.
 *
 * <p><b>검사가 둘인 이유(T029).</b> 규칙이 바뀌면 새 버전 행을 더하고 옛 행은 {@code SUPERSEDED}로 둔다. 새 요청이 옛 버전을 고르면 사용자는
 * 이미 바뀐 규칙으로 계산한 줄 알고 결과를 읽게 되므로 {@link #requireCurrent}가 막는다. 반대로 그 버전으로 이미 접수된 실행은 발행이 늦어지든 실패 뒤
 * 다시 시도하든 같은 규칙으로 계산돼야 하므로 {@link #requireExecutable}이 통과시킨다. 한 검사로 두 경우를 함께 처리하면 둘 중 하나가 깨진다 — 옛
 * 버전을 받아 주면 새 요청이 옛 규칙으로 돌고, 거절하면 규칙이 바뀐 날 대기 중이던 실행과 재시도가 전부 실패한다.
 */
@Service
public class StrategyRulesService {

    private final StrategyRulesRepository repository;

    public StrategyRulesService(StrategyRulesRepository repository) {
        this.repository = repository;
    }

    /**
     * 새 요청이 고른 규칙. 지금 등록된 버전만 통과한다.
     *
     * <ul>
     *   <li>없거나 팀 결정 대기 → {@link ErrorCode#RULES_NOT_REGISTERED}(422). 둘을 같은 오류로 묶는 이유는 사용자에게 "지금
     *       고를 수 없는 전략"으로 같고, 등록표에 행이 있는지 여부는 서버 사정이기 때문이다
     *   <li>새 버전으로 대체됨 → {@link ErrorCode#REGISTRY_VERSION_CHANGED}(409). 화면이 등록표를 읽은 뒤 규칙이 바뀐 것이다.
     *       화면은 두 등록표(003 파라미터·004 규칙)를 다시 받아 바뀐 내용을 보여 준다
     * </ul>
     */
    // noRollbackFor: 이 거절은 접수 트랜잭션 안에서 난다. 기본 규칙이면 이 프록시가 바깥 트랜잭션을 rollback-only 로
    // 표시해, noRollbackFor 로 커밋하려는 BacktestService.create 가 UnexpectedRollbackException(500) 으로 끝난다
    // — quickstart S9 에서 422 대신 500 이 나온 원인이다(T013).
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public StrategyRules requireCurrent(String strategyId, String rulesVersion) {
        StrategyRules rules = requireExecutable(strategyId, rulesVersion);
        if (!rules.isRegistered()) {
            throw versionChanged(rules);
        }
        return rules;
    }

    /**
     * 이미 접수된 실행이 가리키는 규칙. 대체된 옛 버전도 통과한다.
     *
     * <p>작업 조립(첫 발행·재발행)과 실패·취소한 요청의 재시도가 쓴다. 그 실행은 접수할 때 이 버전으로 판정을 받았고, 명세는 옛 행에 그대로 남아 있다. 없거나 팀
     * 결정 대기인 행은 여전히 422다 — 계산할 명세가 없다.
     */
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public StrategyRules requireExecutable(String strategyId, String rulesVersion) {
        StrategyRules rules =
                repository
                        .find(strategyId, rulesVersion)
                        .orElseThrow(() -> notRegistered(strategyId, rulesVersion, "등록되지 않음"));
        if (!rules.isExecutable()) {
            throw notRegistered(strategyId, rulesVersion, "팀 결정 대기: " + rules.decisionRef());
        }
        return rules;
    }

    /**
     * 요청이 버전을 싣지 않는 규칙의 지금 버전. 목표 위험 오버레이처럼 서버가 고르는 규칙에 쓴다.
     *
     * <p>새 요청에만 쓴다. 접수된 실행은 자기가 기록한 버전을 {@link #requireExecutable}로 읽는다 — 여기서 다시 고르면 규칙이 바뀐 날 옛 실행이
     * 새 규칙으로 계산된다.
     */
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public StrategyRules requireCurrent(String strategyId) {
        StrategyRules rules =
                repository
                        .findCurrent(strategyId)
                        .orElseThrow(() -> notRegistered(strategyId, "(current)", "등록되지 않음"));
        if (!rules.isRegistered()) {
            throw notRegistered(
                    strategyId, rules.rulesVersion(), "팀 결정 대기: " + rules.decisionRef());
        }
        return rules;
    }

    /** 등록표 전체(대체된 옛 버전 포함). */
    @Transactional(readOnly = true)
    public List<StrategyRules> findAll() {
        return repository.findAll();
    }

    /** 대체되지 않은 행만, 전략당 하나. 화면이 "지금 무엇을 고를 수 있는가"를 보여줄 때 쓴다. */
    @Transactional(readOnly = true)
    public List<StrategyRules> findCurrent() {
        return repository.findCurrent();
    }

    private BusinessException versionChanged(StrategyRules requested) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("strategyId", requested.strategyId());
        details.put("requestedRulesVersion", requested.rulesVersion());
        repository
                .findCurrent(requested.strategyId())
                .ifPresent(current -> details.put("currentRulesVersion", current.rulesVersion()));
        return new BusinessException(
                ErrorCode.REGISTRY_VERSION_CHANGED,
                ErrorCode.REGISTRY_VERSION_CHANGED.getMessage(),
                details);
    }

    private static BusinessException notRegistered(
            String strategyId, String rulesVersion, String reason) {
        return new BusinessException(
                ErrorCode.RULES_NOT_REGISTERED,
                "strategy rules not available: "
                        + strategyId
                        + "/"
                        + rulesVersion
                        + " — "
                        + reason);
    }
}
