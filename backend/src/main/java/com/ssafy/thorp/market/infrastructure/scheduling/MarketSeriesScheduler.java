package com.ssafy.thorp.market.infrastructure.scheduling;

import com.ssafy.thorp.market.application.service.MarketSeriesService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * 최근 흐름을 기동 직후와 정해진 간격마다 모은다.
 *
 * <p><b>공용 스케줄러 스레드를 쓰지 않는다.</b> {@code @Scheduled}는 스레드 하나를 나눠 쓰는데, KRX 첫 수집은 지표마다 시간 예산(기본 90초)까지
 * 걸린다. 그 동안 발행 재시도·정체 감시 같은 다른 스케줄이 멈추므로 전용 스레드 하나에서 돌린다.
 *
 * <p>{@code market.series.enabled=false}로 끌 수 있다. 끄면 수집만 멈추고 조회는 모아 둔 값이 없어 503이다.
 */
@Component
@ConditionalOnProperty(name = "market.series.enabled", havingValue = "true", matchIfMissing = true)
public class MarketSeriesScheduler implements InitializingBean, DisposableBean {
    private final MarketSeriesService service;
    private final Duration initialDelay;
    private final Duration interval;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    public MarketSeriesScheduler(
            MarketSeriesService service,
            @Value("${market.series.initial-delay:PT5S}") Duration initialDelay,
            @Value("${market.series.refresh-interval:PT6H}") Duration interval) {
        this.service = service;
        this.initialDelay = initialDelay;
        this.interval = interval;
    }

    @Override
    public void afterPropertiesSet() {
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("market-series-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(
                service::refreshAll, Instant.now().plus(initialDelay), interval);
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }
}
