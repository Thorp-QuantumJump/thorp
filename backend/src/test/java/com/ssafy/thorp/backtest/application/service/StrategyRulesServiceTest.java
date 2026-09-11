package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.repository.StrategyRulesRepository;
import com.ssafy.thorp.backtest.domain.model.RulesStatus;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 팀이 규칙을 정하지 않은 전략은 실행을 만들지 않는다(research R-08).
 *
 * <p>여기서 막지 않으면 실행이 만들어진 뒤 워커에서 실패한다. 워커는 메시지에 실려 온 명세만 신뢰하고 자체 기본값을 두지 않기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class StrategyRulesServiceTest {

    @Mock private StrategyRulesRepository repository;
    @InjectMocks private StrategyRulesService service;

    @Test
    @DisplayName("등록된 규칙은 그대로 돌려준다")
    void registeredRulesArePassedThrough() {
        StrategyRules registered =
                new StrategyRules(
                        "EQUAL_WEIGHT",
                        "1",
                        RulesStatus.REGISTERED,
                        false,
                        Map.of("targetWeights", "EQUAL_AMONG_INVESTABLE"),
                        "spec 004 FR-022");
        when(repository.find("EQUAL_WEIGHT", "1")).thenReturn(Optional.of(registered));

        assertThat(service.requireRegistered("EQUAL_WEIGHT", "1")).isSameAs(registered);
    }

    @Test
    @DisplayName("임시 규칙도 실행은 허용한다 — 표시만 다르다(역변동성, PD-02)")
    void provisionalRulesStillRun() {
        StrategyRules provisional =
                new StrategyRules(
                        "INVERSE_VOLATILITY",
                        "1",
                        RulesStatus.REGISTERED,
                        true,
                        Map.of("lookbackDays", 63),
                        "PD-02 미확정");
        when(repository.find("INVERSE_VOLATILITY", "1")).thenReturn(Optional.of(provisional));

        StrategyRules rules = service.requireRegistered("INVERSE_VOLATILITY", "1");

        assertThat(rules.provisional()).isTrue();
    }

    @Test
    @DisplayName("팀 결정 대기 전략은 422로 거부하고 사유를 남긴다(PD-03·04)")
    void pendingDecisionIsRejected() {
        when(repository.find("SECTOR_MOMENTUM", "1"))
                .thenReturn(
                        Optional.of(
                                new StrategyRules(
                                        "SECTOR_MOMENTUM",
                                        "1",
                                        RulesStatus.PENDING_DECISION,
                                        false,
                                        null,
                                        "PD-03 미확정")));

        assertThatThrownBy(() -> service.requireRegistered("SECTOR_MOMENTUM", "1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SECTOR_MOMENTUM")
                .hasMessageContaining("PD-03")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RULES_NOT_REGISTERED);
    }

    @Test
    @DisplayName("등록표에 없는 전략·버전도 같은 422다 — 사용자에게는 '지금 고를 수 없는 전략'으로 같다")
    void unknownRulesAreRejectedTheSameWay() {
        when(repository.find("NOPE", "1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireRegistered("NOPE", "1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RULES_NOT_REGISTERED);
    }
}
