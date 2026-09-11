package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.repository.StrategyRulesRepository;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전략 규칙 등록표 조회(spec 004 research R-08).
 *
 * <p>접수 단계에서 이 서비스를 통과하지 못한 요청은 실행을 만들지 않는다. 워커가 규칙 명세 없이 계산하는 일을 막는 마지막 지점이다 — 워커는 작업 메시지에 실려 온
 * 명세만 신뢰하고 자체 기본값을 두지 않으므로, 명세 없는 요청을 통과시키면 실행이 만들어진 뒤 엔진에서 실패한다.
 */
@Service
public class StrategyRulesService {

    private final StrategyRulesRepository repository;

    public StrategyRulesService(StrategyRulesRepository repository) {
        this.repository = repository;
    }

    /**
     * 실행할 수 있는 규칙을 돌려준다. 없거나 팀 결정 대기면 {@link ErrorCode#RULES_NOT_REGISTERED}(422).
     *
     * <p>둘을 같은 오류로 묶는 이유: 사용자에게는 "지금 고를 수 없는 전략"으로 같고, 등록표에 행이 있는지 여부는 서버 사정이다.
     */
    @Transactional(readOnly = true)
    public StrategyRules requireRegistered(String strategyId, String rulesVersion) {
        StrategyRules rules =
                repository
                        .find(strategyId, rulesVersion)
                        .orElseThrow(() -> notRegistered(strategyId, rulesVersion, "등록되지 않음"));
        if (!rules.isRegistered()) {
            throw notRegistered(strategyId, rulesVersion, "팀 결정 대기: " + rules.decisionRef());
        }
        return rules;
    }

    /** 등록표 전체. 어떤 전략이 실행 가능한지 보여줄 때 쓴다. */
    @Transactional(readOnly = true)
    public List<StrategyRules> findAll() {
        return repository.findAll();
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
