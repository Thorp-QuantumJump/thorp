package com.ssafy.thorp.backtest.presentation.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.backtest.domain.model.CostProfile;
import com.ssafy.thorp.backtest.domain.model.RulesStatus;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code GET /backtests/registry} 응답(spec 004 T025). 계약은 {@code docs/specs/backtest-api/README.md}
 * §1.11과 {@code specs/004-backtest-execution/contracts/rest-api.md}.
 *
 * <p>003 화면이 요청에 실을 {@code strategy.rulesVersion}(필수)과, 비워 보내지 않고 명시할 기본 비용 버전을 여기서 읽는다(003
 * FR-010). 등록표 행을 그대로 내보낼 뿐 새 값을 만들지 않는다 — 워커가 신뢰하는 것은 접수 때 복사된 규칙 명세이고 이 응답은 그 명세의 이름표다.
 *
 * @param strategyRules 전략 규칙 행. 발표 전에는 규칙을 제자리에서 갱신하므로 전략당 한 행이다(버전 교체와 옛 버전 요청 처리는 Phase 10). 화면은
 *     {@link RulesStatus#REGISTERED} 행의 {@code rulesVersion}을 싣고 {@link
 *     RulesStatus#PENDING_DECISION}이면 '분석하기'를 막는다 — 그래서 상태를 걸러내지 않고 그대로 내보낸다
 * @param defaultCostProfile 요청이 {@code costProfileVersion}을 비웠을 때 서버가 쓰는 비용 조건. 화면은 이 {@code
 *     version}을 그대로 명시해 보낸다 — 기본값이 배포로 바뀌어도(T021) 사용자가 본 조건이 그대로 적용되게 한다
 */
public record BacktestRegistryResponse(
        List<StrategyRule> strategyRules, DefaultCostProfile defaultCostProfile) {

    public static BacktestRegistryResponse of(List<StrategyRules> rules, CostProfile costProfile) {
        return new BacktestRegistryResponse(
                rules.stream().map(StrategyRule::of).toList(), DefaultCostProfile.of(costProfile));
    }

    /**
     * 규칙 명세({@code spec})는 싣지 않는다. 화면이 쓰는 것은 버전 문자열과 고를 수 있는지 여부뿐이고, 명세는 접수 때 워커로 복사되는 실행 입력이라 조회
     * 응답에 둘 이유가 없다.
     */
    public record StrategyRule(
            String strategyId, String rulesVersion, RulesStatus status, boolean provisional) {

        static StrategyRule of(StrategyRules rules) {
            return new StrategyRule(
                    rules.strategyId(), rules.rulesVersion(), rules.status(), rules.provisional());
        }
    }

    /** 비용 수치는 다른 응답과 같이 문자열 decimal 이다(단위 bps). */
    public record DefaultCostProfile(
            String version,
            boolean provisional,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal commissionBps,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal spreadBps,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal slippageBps,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal taxBps) {

        static DefaultCostProfile of(CostProfile profile) {
            return new DefaultCostProfile(
                    profile.version(),
                    profile.provisional(),
                    profile.commissionBps(),
                    profile.spreadBps(),
                    profile.slippageBps(),
                    profile.taxBps());
        }
    }
}
