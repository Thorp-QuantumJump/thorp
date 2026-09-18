package com.ssafy.thorp.regime.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 운영에서 임시 대역을 쓰려 하면 <b>기동을 막는다</b> (계약 §4.1).
 *
 * <p>{@code data/} 배치의 {@code reject_fixture_in_production}과 같은 가드를 Backend 쪽에 둔 것이다. 가짜 자료가 운영에
 * 나가는 경로를 양쪽에서 막는다 — 배치는 적재 전에, 여기는 조회 전에.
 *
 * <p><b>왜 기동 실패인가.</b> 요청마다 막으면 운영이 일단 뜨고 나서 화면이 "결과 없음"을 그리는 모습이 되어, 설정이 잘못됐다는 사실이 자료가 없는 상태와 구분되지
 * 않는다. 뜨지 않는 편이 훨씬 시끄럽고 그래서 고쳐진다.
 */
@Configuration
@EnableConfigurationProperties(RegimeProperties.class)
public class RegimeFixtureGuard {

    private static final String PROD_PROFILE = "prod";

    public RegimeFixtureGuard(Environment environment, RegimeProperties properties) {
        String source = properties.getMacro().getSource();
        if (environment.acceptsProfiles(Profiles.of(PROD_PROFILE))
                && RegimeProperties.SOURCE_FIXTURE.equals(source)) {
            throw new IllegalStateException(
                    "prod 프로파일에서는 thorp.regime.macro.source=fixture 로 기동할 수 없습니다. "
                            + "임시 대역은 지어낸 값이라 운영 자료가 될 수 없습니다"
                            + "(contracts/macro-indicator-port.md §4.1). "
                            + "운영에서는 thorp.regime.macro.source=macro 를 씁니다.");
        }
    }
}
