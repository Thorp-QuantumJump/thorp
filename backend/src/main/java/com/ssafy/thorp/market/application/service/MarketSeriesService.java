package com.ssafy.thorp.market.application.service;

import com.ssafy.thorp.market.application.port.MarketDeadline;
import com.ssafy.thorp.market.application.port.MarketSeriesProvider;
import com.ssafy.thorp.market.application.result.MarketSeriesResult;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.exception.MarketDataException.Reason;
import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 일별 지표의 최근 흐름. 요청은 원천을 부르지 않고 모아 둔 값만 돌려준다.
 *
 * <p>KRX는 날짜 하나당 한 번 호출이라 거래일 60개를 요청마다 모으면 요청 예산(8초)을 넘는다. 그래서 기동 직후와 정해진 간격마다 백그라운드로 모아 둔다. 이미 모은
 * 날짜는 다시 부르지 않으므로, 처음 한 번만 오래 걸리고 그 뒤로는 하루치만 부른다.
 */
@Service
public class MarketSeriesService {
    /** 첫 화면 차트가 쓰는 일별 지표. */
    public static final List<MarketCode> CODES =
            List.of(MarketCode.KOSPI, MarketCode.KOSDAQ, MarketCode.USD_KRW, MarketCode.VKOSPI200);

    private static final Logger log = LoggerFactory.getLogger(MarketSeriesService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final List<MarketSeriesProvider> providers;
    private final Clock clock;
    private final MarketSeriesPolicy policy;
    private final Map<MarketCode, Snapshot> snapshots = new ConcurrentHashMap<>();

    public MarketSeriesService(
            List<MarketSeriesProvider> providers,
            @Qualifier("marketClock") Clock clock,
            MarketSeriesPolicy policy) {
        this.providers = List.copyOf(providers);
        this.clock = clock;
        this.policy = policy;
    }

    /** 모아 둔 값이 없으면 NO_DATA(503)다. 마지막 성공이 오래되면 stale로 알린다. */
    public MarketSeriesResult get(MarketCode code) {
        if (!CODES.contains(code)) throw new MarketDataException(Reason.NOT_CONFIGURED);
        Snapshot snapshot = snapshots.get(code);
        if (snapshot == null || snapshot.points().isEmpty())
            throw new MarketDataException(Reason.NO_DATA);
        boolean stale = clock.instant().isAfter(snapshot.retrievedAt().plus(policy.maxAge()));
        return new MarketSeriesResult(code, snapshot.points(), snapshot.retrievedAt(), stale);
    }

    /** 모든 지표를 갱신한다. 부르는 곳은 전용 스레드의 {@code MarketSeriesScheduler}다. */
    public void refreshAll() {
        for (MarketCode code : CODES) {
            try {
                refresh(code);
            } catch (MarketDataException e) {
                // 원천 URL·키·응답 본문을 남기지 않는다. 지표와 사유만 남긴다.
                log.warn("시장 흐름 갱신 실패 code={} reason={}", code, e.reason());
            }
        }
    }

    /** 지표 하나를 갱신한다. 이미 모은 날짜는 넘기고, 창(window) 밖으로 밀려난 날짜는 버린다. */
    void refresh(MarketCode code) {
        MarketSeriesProvider provider =
                providers.stream()
                        .filter(p -> p.supportsSeries(code))
                        .findFirst()
                        .orElseThrow(() -> new MarketDataException(Reason.NOT_CONFIGURED));
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        LocalDate from = today.minusDays(policy.lookbackDays());
        TreeMap<String, MarketObservation> merged = new TreeMap<>();
        Snapshot previous = snapshots.get(code);
        if (previous != null)
            for (MarketObservation point : previous.points())
                if (point.observationDate().compareTo(from.toString()) >= 0)
                    merged.put(point.observationDate(), point);

        List<MarketObservation> fetched =
                provider.fetchSeries(
                        code,
                        from,
                        today,
                        Set.copyOf(merged.keySet()),
                        new MarketDeadline(policy.refreshBudget()));
        for (MarketObservation point : fetched) merged.put(point.observationDate(), point);
        if (merged.isEmpty()) throw new MarketDataException(Reason.NO_DATA);

        List<MarketObservation> points = List.copyOf(merged.values());
        if (points.size() > policy.window())
            points = points.subList(points.size() - policy.window(), points.size());
        snapshots.put(code, new Snapshot(points, clock.instant()));
    }

    private record Snapshot(List<MarketObservation> points, Instant retrievedAt) {}
}
