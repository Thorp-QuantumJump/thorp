package com.ssafy.thorp.macro;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.marketflow.infrastructure.example.MarketFlowExampleDataGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 예시 자료가 운영으로 새지 않는지 본다 (T063, FR-022).
 *
 * <p>예시 자료는 인증키가 없거나 원천을 부를 수 없을 때 개발을 이어 가려고 두는 것이다. 운영에 섞이면 화면은 그럴듯한 값을 그리는데 그 값이 어디서 왔는지는 아무 데도
 * 적혀 있지 않다.
 */
class MacroSampleDataIsolationTest {

    @Test
    @DisplayName("운영 프로필에서는 예시 어댑터를 쓰는 기동이 막힌다")
    void prodRefusesToStartWithExampleData() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> new MarketFlowExampleDataGuard(prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod")
                .hasMessageContaining("FR-022");
    }

    @Test
    @DisplayName("기동을 막는 메시지가 무엇을 해야 하는지까지 말한다")
    void theRefusalSaysWhatToDoNext() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> new MarketFlowExampleDataGuard(prod))
                // 막기만 하고 다음 행동을 말하지 않으면 배포하는 사람이 가드를 지우는 쪽으로 간다.
                .hasMessageContaining("008 스냅샷");
    }

    @Test
    @DisplayName("개발 프로필은 예시 자료로 기동할 수 있다")
    void developmentMayUseExampleData() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThatCode(() -> new MarketFlowExampleDataGuard(local)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("프로필을 지정하지 않아도 막지 않는다")
    void noProfileIsNotProduction() {
        // 프로필이 없는 것은 시험·로컬이다. 여기서 막으면 개발이 시작부터 멈춘다.
        assertThatCode(() -> new MarketFlowExampleDataGuard(new MockEnvironment()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("운영 프로필이 다른 프로필과 함께 켜져도 막는다")
    void prodCombinedWithAnotherProfileStillRefuses() {
        // prod + local 같은 조합으로 가드를 우회하지 못하게 한다.
        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("local", "prod");

        assertThatThrownBy(() -> new MarketFlowExampleDataGuard(mixed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("가드를 속성으로 끌 수 있는 길이 없다")
    void theGuardHasNoOffSwitch() {
        // 끄는 길을 두면 배포가 급할 때 켜지고, 켜진 채로 잊힌다.
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        prod.setProperty("thorp.marketflow.example.enabled", "true");
        prod.setProperty("thorp.marketflow.allow-example-data", "true");

        assertThatThrownBy(() -> new MarketFlowExampleDataGuard(prod))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("막는 메시지가 어느 파일이 예시 자료인지 가리킨다")
    void theRefusalNamesTheExampleResource() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        // 어느 파일이 문제인지 없으면 받는 사람이 저장소를 뒤져야 한다.
        assertThatThrownBy(() -> new MarketFlowExampleDataGuard(prod))
                .hasMessageContaining("example");
    }
}
