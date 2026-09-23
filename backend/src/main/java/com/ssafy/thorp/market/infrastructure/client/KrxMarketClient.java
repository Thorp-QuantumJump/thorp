package com.ssafy.thorp.market.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.thorp.market.application.port.MarketDeadline;
import com.ssafy.thorp.market.application.port.MarketProvider;
import com.ssafy.thorp.market.application.port.MarketSeriesProvider;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.exception.MarketDataException.Reason;
import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import com.ssafy.thorp.market.infrastructure.config.MarketApiProperties;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class KrxMarketClient implements MarketProvider, MarketSeriesProvider {
    private final MarketHttpClient http;
    private final MarketApiProperties.Krx config;
    private final Duration pace;
    private final Clock clock;

    public KrxMarketClient(
            MarketHttpClient http,
            MarketApiProperties properties,
            @Qualifier("marketClock") Clock clock) {
        this.http = http;
        this.config = properties.getKrx();
        this.pace = properties.getSeries().getKrxPace();
        this.clock = clock;
    }

    @Override
    public boolean supports(MarketCode code) {
        return code == MarketCode.KOSPI
                || code == MarketCode.KOSDAQ
                || code == MarketCode.VKOSPI200;
    }

    @Override
    public boolean supportsSeries(MarketCode code) {
        return supports(code);
    }

    @Override
    public MarketObservation fetch(MarketCode code, MarketDeadline deadline) {
        String key = MarketHttpClient.key(config.getApiKey());
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        for (int offset = 0; offset < config.getLookbackDays(); offset++) {
            LocalDate date = today.minusDays(offset);
            if (isWeekend(date)) continue;
            // 휴일/당일 미게시인 빈 응답만 이전 날짜로 이동한다. 잘못된 식별자는 숨기지 않는다.
            Optional<MarketObservation> observation = day(code, key, date, deadline);
            if (observation.isPresent()) return observation.get();
        }
        throw new MarketDataException(Reason.NO_DATA);
    }

    /**
     * 날짜마다 한 번씩 부른다. 최근 날짜부터 거슬러 가며 이미 모은 날짜는 건너뛴다. 원천에 부담을 주지 않도록 호출 사이에 간격을 두고, 시간 예산이 다 되면 그때까지
     * 모은 값만 돌려준다.
     */
    @Override
    public List<MarketObservation> fetchSeries(
            MarketCode code,
            LocalDate from,
            LocalDate to,
            Set<String> known,
            MarketDeadline deadline) {
        String key = MarketHttpClient.key(config.getApiKey());
        List<MarketObservation> collected = new ArrayList<>();
        boolean first = true;
        for (LocalDate date = to; !date.isBefore(from); date = date.minusDays(1)) {
            if (isWeekend(date) || known.contains(date.toString())) continue;
            try {
                deadline.remaining();
                if (!first) sleep(pace);
                first = false;
                day(code, key, date, deadline).ifPresent(collected::add);
            } catch (MarketDataException e) {
                if (e.reason() == Reason.TIMEOUT) break;
                // 한 날짜의 결측(휴일에 채워진 다른 지수만 있는 경우 등)은 건너뛴다. 인증 실패 등은 그대로 알린다.
                if (e.reason() != Reason.NO_DATA) throw e;
            }
        }
        collected.sort(Comparator.comparing(MarketObservation::observationDate));
        return collected;
    }

    /** 하루치. 빈 응답(휴일·미게시)은 비어 있음, 응답은 있는데 지수가 없으면 NO_DATA다. */
    private Optional<MarketObservation> day(
            MarketCode code, String key, LocalDate date, MarketDeadline deadline) {
        String endpoint =
                switch (code) {
                    case KOSPI -> "kospi_dd_trd";
                    case KOSDAQ -> "kosdaq_dd_trd";
                    case VKOSPI200 -> "drvprod_dd_trd";
                    default -> throw new IllegalArgumentException("KRX 지원 코드가 아닙니다.");
                };
        String basDd = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        JsonNode root =
                http.json(
                        MarketHttpClient.query(
                                config.getBaseUrl() + "/" + endpoint, Map.of("basDd", basDd)),
                        Map.of("AUTH_KEY", key),
                        deadline);
        if (root == null) throw new MarketDataException(Reason.INVALID_RESPONSE);
        JsonNode rows = root.path("OutBlock_1");
        MarketValues.require(rows.isArray());
        for (JsonNode row : rows) {
            if (!matches(code, row.path("IDX_NM").asText())) continue;
            MarketValues.require(basDd.equals(row.path("BAS_DD").asText()));
            var observation =
                    MarketValues.observation(row.path("CLSPRC_IDX").asText(), "pt", basDd, false);
            if (observation.isPresent()) return observation;
        }
        if (!rows.isEmpty()) throw new MarketDataException(Reason.NO_DATA);
        return Optional.empty();
    }

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) return;
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException(Reason.TIMEOUT);
        }
    }

    private boolean matches(MarketCode code, String name) {
        return switch (code) {
            case KOSPI -> name.equals("코스피") || name.equals("KOSPI");
            case KOSDAQ -> name.equals("코스닥") || name.equals("KOSDAQ");
            case VKOSPI200 ->
                    name.equals(config.getVolatilityIndexName()) || name.equals("V-KOSPI 200");
            default -> false;
        };
    }
}
