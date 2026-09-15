package com.ssafy.thorp.news.infrastructure.config;

import com.ssafy.thorp.news.application.repository.NewsCollectionStateRepository;
import com.ssafy.thorp.news.application.service.NewsFreshnessPolicy;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 뉴스 신선도를 전용 지표로만 노출한다(spec FR-024, plan "보관과 관측성").
 *
 * <p><b>항상 {@code UP}을 돌려준다.</b> 뉴스가 오래됐거나 모든 뉴스원이 실패해도 서비스가 비정상인 것은 아니다. 뉴스는 시장 흐름의 보조 맥락이고,
 * 백테스트·섹터·시장 자료는 뉴스와 무관하게 동작한다. 여기서 {@code DOWN}을 돌려주면 Actuator 전체 집계가 내려가고, 배포 판정과 경보가 외부 뉴스원의 사정에
 * 끌려간다.
 *
 * <p>대신 신선도를 {@code details}에 담는다. 운영자는 이 값을 보고 판단하고, 자동 판정에는 쓰지 않는다.
 *
 * <p>bean 이름이 지표 이름이 된다 — {@code /actuator/health/newsFreshness}로 이 항목만 따로 볼 수 있다.
 */
@Component("newsFreshness")
public class NewsHealthIndicator implements HealthIndicator {

    private final NewsCollectionStateRepository collectionStates;
    private final NewsFreshnessPolicy freshness;
    private final NewsProperties properties;
    private final Clock clock;

    public NewsHealthIndicator(
            NewsCollectionStateRepository collectionStates,
            NewsFreshnessPolicy freshness,
            NewsProperties properties,
            Clock clock) {
        this.collectionStates = collectionStates;
        this.freshness = freshness;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Instant now = clock.instant();
        Instant lastSuccessAt = collectionStates.findLatestSuccessAt(false).orElse(null);
        boolean stale = freshness.isStale(lastSuccessAt, now, properties.staleThreshold());

        Health.Builder health = Health.up();
        health.withDetail(
                "lastSuccessAt", lastSuccessAt == null ? "NEVER" : lastSuccessAt.toString());
        health.withDetail("stale", stale);
        health.withDetail("staleThreshold", properties.staleThreshold().toString());
        return health.build();
    }
}
