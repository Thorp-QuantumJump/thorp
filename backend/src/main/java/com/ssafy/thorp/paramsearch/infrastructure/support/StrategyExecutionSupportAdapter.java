package com.ssafy.thorp.paramsearch.infrastructure.support;

import com.ssafy.thorp.backtest.application.service.StrategyRulesService;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.paramsearch.application.port.StrategyExecutionSupportPort;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 004(backtest)가 실행을 지원하는 전략 목록(research R8).
 *
 * <p>004의 전략 규칙 등록표에서 <b>실행 가능한 규칙이 등록된</b> 전략만 돌려준다. 규칙이 아직 팀 결정 대기({@code PENDING_DECISION})인 전략은
 * 접수 단계에서 422로 거부되므로 탐색 대상이 될 수 없다 — 그런 전략을 그대로 발행하면 후보 전량이 실패로 끝나 "제약 위반"과 "낮은 수익"의 구분이 흐려진다(원칙
 * VI).
 *
 * <p>004가 워커에 전략을 추가하고 등록표에 규칙을 넣으면 007은 코드 변경 없이 그 전략을 얻는다. <b>여기에 전략 ID를 적지 않는 이유가 그것이다</b>
 * (FR-025·FR-026).
 *
 * <p>이 어댑터가 {@code paramsearch}에 있는 이유: 반대 방향이면 {@code backtest}가 007의 포트를 알게 되어 "backtest는
 * paramsearch를 참조하지 않는다"는 계약이 깨진다(contracts/param-search-internal.md §3).
 */
@Component
public class StrategyExecutionSupportAdapter implements StrategyExecutionSupportPort {

    private final StrategyRulesService strategyRulesService;

    public StrategyExecutionSupportAdapter(StrategyRulesService strategyRulesService) {
        this.strategyRulesService = strategyRulesService;
    }

    @Override
    public Map<String, String> supportedStrategies() {
        // 전략마다 지금 고를 수 있는 규칙 행은 하나다(004 T029, 부분 유니크 인덱스). 접수 시점의 그 버전을 묶음에
        // 고정해야 후보들이 같은 규칙으로 계산된다(FR-012). 새 버전으로 대체된 옛 행은 여기 오지 않는다 — 버전 문자열의
        // 크기로 고르면 "10" < "9" 처럼 문자열 순서가 버전 순서와 어긋난다.
        return strategyRulesService.findCurrent().stream()
                .filter(StrategyRules::isRegistered)
                .collect(
                        Collectors.toUnmodifiableMap(
                                StrategyRules::strategyId, StrategyRules::rulesVersion));
    }
}
