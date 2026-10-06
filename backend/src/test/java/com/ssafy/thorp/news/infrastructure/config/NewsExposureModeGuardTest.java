package com.ssafy.thorp.news.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.news.domain.vo.NewsExposureMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * 운영 환경이 내부 노출 모드로 뜨지 못하게 막는 규칙(spec FR-016).
 *
 * <p>노출 모드는 설정 한 줄인데 그 한 줄이 곧 공개 범위다. 잘못 들어가면 회신하지 않은 매체의 제목과 매체명이 인증 없는 주소로 나가고, 그 실수는 배포된 뒤에도 아무
 * 신호를 내지 않는다 — 응답이 오히려 더 풍성해 보이기 때문이다.
 *
 * <p>그래서 기동을 실패시킨다. 시끄럽고 즉시 드러나는 실패가 조용히 새는 것보다 낫다.
 */
class NewsExposureModeGuardTest {

    private static NewsProperties properties(NewsExposureMode mode) {
        return new NewsProperties(
                mode,
                Duration.ofMinutes(30),
                Duration.ofMinutes(15),
                Duration.ofMinutes(15),
                LocalDate.of(2026, 11, 7),
                "Asia/Seoul",
                93,
                20,
                3,
                Duration.ofHours(48),
                new NewsProperties.Http(
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        10_485_760L,
                        Set.of("application/xml"),
                        Set.of("application/json"),
                        List.of("api.gdeltproject.org")));
    }

    private static NewsExposureModeGuard guard(NewsExposureMode mode, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new NewsExposureModeGuard(properties(mode), environment);
    }

    @Test
    @DisplayName("운영 프로필에서 내부 모드면 기동을 막는다")
    void 운영은_내부_모드_금지() {
        assertThatThrownBy(() -> guard(NewsExposureMode.INTERNAL, "prod").verify())
                .isInstanceOf(IllegalStateException.class)
                .as("메시지가 무엇이 잘못됐는지 말해 줘야 배포 로그만 보고 고칠 수 있다")
                .hasMessageContaining("exposure-mode");
    }

    @Test
    @DisplayName("운영 프로필이라도 공개 모드면 지나간다")
    void 운영의_공개_모드() {
        assertThatCode(() -> guard(NewsExposureMode.PUBLIC, "prod").verify())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("내부 검증 환경은 내부 모드로 뜰 수 있다")
    void 내부_환경은_허용() {
        assertThatCode(() -> guard(NewsExposureMode.INTERNAL, "local").verify())
                .as("여기까지 막으면 회신 전에 수집을 확인할 길이 사라진다(spec FR-016)")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("노출 모드 설정을 빠뜨리면 공개 모드가 기본이라 운영 프로필에서도 뜬다")
    void 기본값은_공개() {
        // record를 직접 만들면 @DefaultValue를 지나지 않는다. 설정 없이 바인딩해 실제 기본값을 읽는다.
        NewsProperties defaults =
                new Binder(new MapConfigurationPropertySource(Map.of()))
                        .bindOrCreate("thorp.news", NewsProperties.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThat(defaults.exposureMode())
                .as("설정을 빠뜨렸을 때 닫히는 쪽으로 틀려야 한다")
                .isEqualTo(NewsExposureMode.PUBLIC);
        assertThatCode(() -> new NewsExposureModeGuard(defaults, environment).verify())
                .doesNotThrowAnyException();
    }
}
