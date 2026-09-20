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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 팀이 규칙을 정하지 않은 전략은 실행을 만들지 않는다(research R-08). 대체된 옛 규칙은 새 요청만 막고 이미 접수된 실행은 통과시킨다(T029).
 *
 * <p>여기서 막지 않으면 실행이 만들어진 뒤 워커에서 실패한다. 워커는 메시지에 실려 온 명세만 신뢰하고 자체 기본값을 두지 않기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class StrategyRulesServiceTest {

    @Mock private StrategyRulesRepository repository;
    @InjectMocks private StrategyRulesService service;

    private static final StrategyRules INVERSE_VOL_1_SUPERSEDED =
            new StrategyRules(
                    "INVERSE_VOLATILITY",
                    "1",
                    RulesStatus.SUPERSEDED,
                    true,
                    Map.of("lookbackDays", 63),
                    "PD-02 미확정");

    private static final StrategyRules INVERSE_VOL_2 =
            new StrategyRules(
                    "INVERSE_VOLATILITY",
                    "2",
                    RulesStatus.REGISTERED,
                    false,
                    Map.of("lookbackDays", 126),
                    "PD-02 확정");

    private static final StrategyRules MOMENTUM_PENDING =
            new StrategyRules(
                    "SECTOR_MOMENTUM", "1", RulesStatus.PENDING_DECISION, false, null, "PD-03 미확정");

    @Nested
    @DisplayName("새 요청(requireCurrent)")
    class Current {

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

            assertThat(service.requireCurrent("EQUAL_WEIGHT", "1")).isSameAs(registered);
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

            StrategyRules rules = service.requireCurrent("INVERSE_VOLATILITY", "1");

            assertThat(rules.provisional()).isTrue();
        }

        @Test
        @DisplayName("팀 결정 대기 전략은 422로 거부하고 사유를 남긴다(PD-03·04)")
        void pendingDecisionIsRejected() {
            when(repository.find("SECTOR_MOMENTUM", "1")).thenReturn(Optional.of(MOMENTUM_PENDING));

            assertThatThrownBy(() -> service.requireCurrent("SECTOR_MOMENTUM", "1"))
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

            assertThatThrownBy(() -> service.requireCurrent("NOPE", "1"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.RULES_NOT_REGISTERED);
        }

        @Test
        @DisplayName("대체된 옛 버전은 409 REGISTRY_VERSION_CHANGED 이고 지금 버전을 함께 알려 준다")
        void supersededVersionIsAConflict() {
            when(repository.find("INVERSE_VOLATILITY", "1"))
                    .thenReturn(Optional.of(INVERSE_VOL_1_SUPERSEDED));
            when(repository.findCurrent("INVERSE_VOLATILITY"))
                    .thenReturn(Optional.of(INVERSE_VOL_2));

            assertThatThrownBy(() -> service.requireCurrent("INVERSE_VOLATILITY", "1"))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            e -> {
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.REGISTRY_VERSION_CHANGED);
                                assertThat(e.getDetails())
                                        .containsEntry("strategyId", "INVERSE_VOLATILITY")
                                        .containsEntry("requestedRulesVersion", "1")
                                        .containsEntry("currentRulesVersion", "2");
                            });
        }

        @Test
        @DisplayName("버전을 싣지 않는 규칙(오버레이)은 지금 행을 고른다")
        void currentOfStrategyPicksTheLiveRow() {
            when(repository.findCurrent("INVERSE_VOLATILITY"))
                    .thenReturn(Optional.of(INVERSE_VOL_2));

            assertThat(service.requireCurrent("INVERSE_VOLATILITY")).isSameAs(INVERSE_VOL_2);
        }

        @Test
        @DisplayName("지금 행이 팀 결정 대기면 버전을 싣지 않는 규칙도 422다")
        void currentOfStrategyRejectsPending() {
            when(repository.findCurrent("SECTOR_MOMENTUM"))
                    .thenReturn(Optional.of(MOMENTUM_PENDING));

            assertThatThrownBy(() -> service.requireCurrent("SECTOR_MOMENTUM"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.RULES_NOT_REGISTERED);
        }
    }

    @Nested
    @DisplayName("이미 접수된 실행(requireExecutable)")
    class Executable {

        @Test
        @DisplayName("대체된 옛 버전도 명세 그대로 돌려준다 — 그 버전으로 접수된 실행의 발행·재시도")
        void supersededVersionStillExecutes() {
            when(repository.find("INVERSE_VOLATILITY", "1"))
                    .thenReturn(Optional.of(INVERSE_VOL_1_SUPERSEDED));

            StrategyRules rules = service.requireExecutable("INVERSE_VOLATILITY", "1");

            assertThat(rules).isSameAs(INVERSE_VOL_1_SUPERSEDED);
            assertThat(rules.spec()).containsEntry("lookbackDays", 63);
        }

        @Test
        @DisplayName("팀 결정 대기는 여전히 422다 — 계산할 명세가 없다")
        void pendingDecisionIsStillRejected() {
            when(repository.find("SECTOR_MOMENTUM", "1")).thenReturn(Optional.of(MOMENTUM_PENDING));

            assertThatThrownBy(() -> service.requireExecutable("SECTOR_MOMENTUM", "1"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.RULES_NOT_REGISTERED);
        }

        @Test
        @DisplayName("행이 없으면 422다")
        void missingRowIsRejected() {
            when(repository.find("INVERSE_VOLATILITY", "9")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requireExecutable("INVERSE_VOLATILITY", "9"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.RULES_NOT_REGISTERED);
        }
    }
}
