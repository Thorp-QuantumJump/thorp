package com.ssafy.thorp.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.DockerClientFactory;

/**
 * Docker를 요구하는 환경에서 Docker가 실제로 붙는지 확인한다.
 *
 * <p>{@link PostgresTestSupport}는 {@code disabledWithoutDocker = true}라서, Docker가 없으면 DB 테스트가
 * <b>실패가 아니라 스킵</b>이 되고 빌드는 초록색으로 끝난다. 로컬에서는 그 편이 편하지만 CI에서는 위험하다. 마이그레이션과 Entity 매핑을 실제로 맞춰 보는 검증이
 * 통째로 비어 있는데도 통과한 것처럼 보이기 때문이다.
 *
 * <p>그래서 CI는 {@code THORP_REQUIRE_DOCKER=true}를 넣고 돌린다. 그 환경에서 Docker가 없으면 이 테스트가 실패해 빌드를 멈춘다. 환경
 * 변수가 없는 로컬에서는 이 테스트 자체가 실행되지 않으므로 개발자 흐름은 그대로다.
 *
 * <p>constitution "스키마 변경 규칙": 실행되지 않은 검증(환경이 없어 건너뛴 경우를 포함한다)을 통과로 보지 않는다.
 */
@EnabledIfEnvironmentVariable(
        named = "THORP_REQUIRE_DOCKER",
        matches = "true",
        disabledReason = "Docker를 요구하지 않는 환경이다. CI는 이 변수를 true로 설정한다")
class RequiredDockerTest {

    @Test
    @DisplayName("Docker를 요구하는 환경에서는 Docker에 붙을 수 있다")
    void dockerIsAvailable() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as(
                        "THORP_REQUIRE_DOCKER=true인데 Docker에 붙지 못했다."
                                + " 이대로 두면 DB·메시징 통합 테스트가 모두 스킵된 채 빌드가 통과한다")
                .isTrue();
    }
}
