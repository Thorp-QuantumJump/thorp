package com.ssafy.thorp.news.infrastructure.config;

import com.ssafy.thorp.news.domain.vo.NewsExposureMode;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 운영 환경이 내부 노출 모드로 뜨는 것을 막는다(spec FR-016, plan "조회와 상태").
 *
 * <p>노출 모드는 배포 단위가 정한다. 내부 검증 환경은 {@code INTERNAL}로 띄워 회신 전 매체의 기사까지 보며 수집이 제대로 도는지 확인하고, 공개 환경은
 * {@code PUBLIC}으로 띄워 허가된 매체만 내보낸다. 클라이언트는 이 값을 고를 수 없다.
 *
 * <p><b>그래서 설정 한 줄이 곧 공개 범위다.</b> 운영에 {@code INTERNAL}이 들어가면 회신하지 않은 매체의 제목과 매체명이 인증 없는 주소로 나간다. 그런
 * 실수는 배포된 뒤에도 아무 신호를 내지 않는다 — 응답이 오히려 더 풍성해 보이기 때문이다.
 *
 * <p>그래서 두 겹으로 막는다. 기본값이 {@code PUBLIC}이라 설정을 빠뜨리면 닫히는 쪽으로 틀리고, 운영 프로필에서 {@code INTERNAL}이면 기동 자체가
 * 실패한다. 기동 실패는 시끄럽고 즉시 드러나므로 조용히 새는 것보다 낫다.
 *
 * <p>내부 모드로 뜰 때는 경고를 남긴다. 그 로그가 없으면 어느 환경이 무엇을 내보내고 있는지 나중에 확인할 방법이 로그에 없다.
 */
@Component
public class NewsExposureModeGuard {

    private static final Logger log = LoggerFactory.getLogger(NewsExposureModeGuard.class);

    /** 이 프로필로 뜨는 환경은 공개 환경으로 본다. */
    private static final List<String> PUBLIC_PROFILES = List.of("prod");

    private final NewsProperties properties;
    private final Environment environment;

    public NewsExposureModeGuard(NewsProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void verify() {
        NewsExposureMode mode = properties.exposureMode();
        if (mode == NewsExposureMode.PUBLIC) {
            return;
        }
        if (isPublicEnvironment()) {
            throw new IllegalStateException(
                    "운영 프로필에서는 thorp.news.exposure-mode를 INTERNAL로 둘 수 없다."
                            + " 회신하지 않은 매체의 기사가 공개 주소로 나간다");
        }
        log.warn(
                "뉴스 노출 모드가 INTERNAL이다. 허가 회신과 무관하게 모든 매체의 기사가 응답에 실린다."
                        + " 내부 검증 환경에서만 쓴다 (spec FR-016)");
    }

    private boolean isPublicEnvironment() {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(PUBLIC_PROFILES::contains);
    }
}
