package com.ssafy.thorp.regime.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * 운영에서 임시 대역으로 기동할 수 없다 (계약 §4.1, tasks.md T085).
 *
 * <p>{@code data/} 배치의 {@code reject_fixture_in_production}과 짝을 이루는 가드다 — 배치는 적재 전에, 여기는 조회 전에 막는다.
 * 한쪽만 있으면 설정 실수 하나로 가짜 자료가 운영 경로에 나간다.
 */
@DisplayName("운영 프로파일 임시 대역 가드")
class RegimeProfileGuardTest {

    @Test
    @DisplayName("prod + fixture 면 기동에 실패한다")
    void prodWithFixtureFailsToStart() {
        assertThatThrownBy(() -> guard("prod", RegimeProperties.SOURCE_FIXTURE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixture")
                .hasMessageContaining("prod");
    }

    @Test
    @DisplayName("prod + macro 는 통과한다")
    void prodWithMacroStarts() {
        assertThatCode(() -> guard("prod", RegimeProperties.SOURCE_MACRO))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "test"})
    @DisplayName("운영이 아니면 임시 대역으로 기동한다")
    void nonProdWithFixtureStarts(String profile) {
        assertThatCode(() -> guard(profile, RegimeProperties.SOURCE_FIXTURE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("설정에 기본값이 없다")
    void hasNoDefaultSource() {
        // 기본값을 두면 설정을 빠뜨린 환경이 조용히 임시 대역으로 돌고, 그때 만들어진 응답은
        // 가짜 자료인 채로 정상으로 보인다.
        assertThat(new RegimeProperties.Macro().getSource()).isNull();
    }

    private static RegimeFixtureGuard guard(String profile, String source) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        RegimeProperties properties = new RegimeProperties();
        RegimeProperties.Macro macro = new RegimeProperties.Macro();
        macro.setSource(source);
        properties.setMacro(macro);
        return new RegimeFixtureGuard(environment, properties);
    }
}
