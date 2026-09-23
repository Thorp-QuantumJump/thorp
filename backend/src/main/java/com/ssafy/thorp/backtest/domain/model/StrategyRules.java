package com.ssafy.thorp.backtest.domain.model;

import java.util.Map;
import java.util.Objects;

/**
 * 전략 규칙 버전(spec 004 research R-08, data-model {@code strategy_rules}).
 *
 * <p>워커는 작업 메시지에 실려 온 규칙 명세만 신뢰하고 자체 기본값을 두지 않는다. 그래서 명세는 실행마다 이 등록표에서 복사돼 나간다.
 *
 * <p>{@link RulesStatus#PENDING_DECISION}은 팀이 계산 규칙을 아직 정하지 않은 전략이다. 명세가 없으므로 실행할 수 없고 접수 단계에서
 * 거부한다. 출발안을 임의로 넣지 않는 이유는 나중에 확정값과 결과 버전이 갈리기 때문이다.
 *
 * <p>{@link RulesStatus#SUPERSEDED}는 새 버전에 자리를 내준 옛 규칙이다. 새 요청은 고를 수 없지만 명세는 남아 있어서, 그 버전으로 접수된 실행은
 * 끝까지 같은 명세로 계산된다(T029).
 *
 * @param spec 규칙 명세. {@code PENDING_DECISION}이면 null
 * @param provisional true = 팀 결정(PD) 전 임시 규칙. 결과 {@code versions.rules.provisional}로 노출한다
 * @param decisionRef 근거(PD 번호·spec 조항)
 */
public record StrategyRules(
        String strategyId,
        String rulesVersion,
        RulesStatus status,
        boolean provisional,
        Map<String, Object> spec,
        String decisionRef) {

    public StrategyRules {
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId must not be blank");
        }
        if (rulesVersion == null || rulesVersion.isBlank()) {
            throw new IllegalArgumentException("rulesVersion must not be blank");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (status != RulesStatus.PENDING_DECISION && spec == null) {
            throw new IllegalArgumentException(
                    status + " rules must carry a spec: " + strategyId + "/" + rulesVersion);
        }
        if (status == RulesStatus.PENDING_DECISION && spec != null) {
            throw new IllegalArgumentException(
                    "PENDING_DECISION rules must not carry a spec: "
                            + strategyId
                            + "/"
                            + rulesVersion);
        }
        spec = spec == null ? null : Map.copyOf(spec);
    }

    /** 새 요청이 고를 수 있는 현재 규칙인가. */
    public boolean isRegistered() {
        return status == RulesStatus.REGISTERED;
    }

    /** 이미 접수된 실행을 이 명세로 계산할 수 있는가. 대체된 옛 규칙도 명세가 남아 있으므로 계산할 수 있다. */
    public boolean isExecutable() {
        return status == RulesStatus.REGISTERED || status == RulesStatus.SUPERSEDED;
    }

    /** 이 행을 가리키는 이름표({@code 전략/버전}). 실행 기록과 작업 메시지에 이 표기로 남긴다. */
    public RulesRef ref() {
        return new RulesRef(strategyId, rulesVersion);
    }

    /** 대체되지 않은 행인가. 전략마다 이런 행은 하나뿐이다(마이그레이션의 부분 유니크 인덱스). */
    public boolean isCurrent() {
        return status != RulesStatus.SUPERSEDED;
    }
}
