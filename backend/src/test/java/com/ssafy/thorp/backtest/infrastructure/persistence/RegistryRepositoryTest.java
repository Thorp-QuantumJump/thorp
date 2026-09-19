package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.domain.model.CostProfile;
import com.ssafy.thorp.backtest.domain.model.RulesStatus;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.support.RepositoryTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 마이그레이션이 넣은 등록표 시드가 도메인 모델로 그대로 올라오는지 본다(R-07·R-08).
 *
 * <p>{@code FlywayMigrationTest}는 같은 행을 SQL로 확인한다. 여기서 한 번 더 보는 것은 <b>매핑</b>이다 — enum 문자열과 jsonb
 * 명세가 도메인 타입으로 변환되는 경로는 SQL 단언이 지나간다. 시드 값이 바뀌면 두 곳이 함께 깨져야 한다.
 */
class RegistryRepositoryTest extends RepositoryTestSupport {

    @Autowired private CostProfileRepositoryImpl costProfiles;
    @Autowired private StrategyRulesRepositoryImpl strategyRules;

    @Test
    @DisplayName("데모 비용 조건이 임시 표시와 함께 읽힌다")
    void demoCostProfileIsProvisional() {
        CostProfile profile = costProfiles.find("PROVISIONAL-DEMO-1").orElseThrow();

        assertThat(profile.provisional()).isTrue();
        assertThat(profile.commissionBps()).isEqualByComparingTo("0");
        assertThat(profile.spreadBps()).isEqualByComparingTo("0");
        assertThat(profile.slippageBps()).isEqualByComparingTo("5");
        assertThat(profile.taxBps()).isEqualByComparingTo("0");
        assertThat(profile.escalation()).as("비용 상승 시나리오는 PD-07 미정").isNull();
        assertThat(profile.note()).contains("ADR-0004");
    }

    @Test
    @DisplayName("확정 비용 조건(PD-07)이 확정 표시와 함께 읽힌다")
    void confirmedCostProfileIsNotProvisional() {
        CostProfile profile = costProfiles.find("KR-ETF-2026-09").orElseThrow();

        assertThat(profile.provisional()).isFalse();
        assertThat(profile.commissionBps()).isEqualByComparingTo("1.5");
        assertThat(profile.spreadBps()).isEqualByComparingTo("3");
        assertThat(profile.slippageBps()).isEqualByComparingTo("5");
        assertThat(profile.taxBps()).isEqualByComparingTo("0");
        assertThat(profile.escalation()).as("비용 상승 시나리오는 아직 정하지 않았다").isNull();
        assertThat(profile.note()).contains("PD-07");
    }

    @Test
    @DisplayName("모르는 비용 버전은 빈 Optional이다")
    void unknownCostProfileIsEmpty() {
        assertThat(costProfiles.find("NOPE")).isEmpty();
    }

    @Test
    @DisplayName("등록된 전략은 jsonb 명세가 Map으로 올라온다")
    void registeredRulesCarrySpec() {
        StrategyRules inverseVol = strategyRules.find("INVERSE_VOLATILITY", "1").orElseThrow();

        assertThat(inverseVol.status()).isEqualTo(RulesStatus.REGISTERED);
        assertThat(inverseVol.isRegistered()).isTrue();
        assertThat(inverseVol.provisional()).as("PD-02 대기라 임시 규칙").isTrue();
        assertThat(inverseVol.spec())
                .containsEntry("lookbackDays", 63)
                .containsEntry("zeroVolatility", "EXCLUDE_THEN_ALLOCATE");
        assertThat(inverseVol.decisionRef()).contains("PD-02");
    }

    @Test
    @DisplayName("섹터 모멘텀은 PD-03 확정 값으로 등록돼 있다")
    void momentumRulesCarryTheDecidedSpec() {
        StrategyRules momentum = strategyRules.find("SECTOR_MOMENTUM", "1").orElseThrow();

        assertThat(momentum.status()).isEqualTo(RulesStatus.REGISTERED);
        assertThat(momentum.isRegistered()).isTrue();
        assertThat(momentum.provisional()).isFalse();
        assertThat(momentum.spec())
                .containsEntry("scoreMethod", "SIMPLE_RETURN")
                .containsEntry("priceField", "ADJUSTED_CLOSE")
                .containsEntry("tieBreak", "INSTRUMENT_ID_ASC")
                .containsEntry("noSelection", "CASH")
                .containsEntry("capOverflow", "CASH");
        assertThat(momentum.decisionRef()).contains("PD-03");
    }

    @Test
    @DisplayName("팀 결정 대기로 남은 전략은 없다 — 다섯이 모두 확정됐다")
    void noStrategyIsStillPending() {
        assertThat(strategyRules.findAll())
                .filteredOn(rules -> rules.status() == RulesStatus.PENDING_DECISION)
                .isEmpty();
    }

    @Test
    @DisplayName("다섯 전략과 목표 위험 오버레이가 모두 등록표에 있다")
    void allStrategiesAreListed() {
        assertThat(strategyRules.findAll())
                .extracting(StrategyRules::strategyId)
                .containsExactlyInAnyOrder(
                        "FIXED_WEIGHT",
                        "EQUAL_WEIGHT",
                        "INVERSE_VOLATILITY",
                        "SECTOR_MOMENTUM",
                        "MINIMUM_VARIANCE",
                        "TARGET_RISK_OVERLAY");
    }
}
