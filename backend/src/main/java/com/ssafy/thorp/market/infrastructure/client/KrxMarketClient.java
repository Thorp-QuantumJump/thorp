package com.ssafy.thorp.market.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.thorp.market.application.port.MarketDeadline;
import com.ssafy.thorp.market.application.port.MarketProvider;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.exception.MarketDataException.Reason;
import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import com.ssafy.thorp.market.infrastructure.config.MarketApiProperties;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class KrxMarketClient implements MarketProvider {
    private final MarketHttpClient http;
    private final MarketApiProperties.Krx config;
    private final Clock clock;

    public KrxMarketClient(
            MarketHttpClient http,
            MarketApiProperties properties,
            @Qualifier("marketClock") Clock clock) {
        this.http = http;
        this.config = properties.getKrx();
        this.clock = clock;
    }

    @Override
    public boolean supports(MarketCode code) {
        return code == MarketCode.KOSPI
                || code == MarketCode.KOSDAQ
                || code == MarketCode.VKOSPI200;
    }

    @Override
    public MarketObservation fetch(MarketCode code, MarketDeadline deadline) {
        String endpoint =
                switch (code) {
                    case KOSPI -> "kospi_dd_trd";
                    case KOSDAQ -> "kosdaq_dd_trd";
                    case VKOSPI200 -> "drvprod_dd_trd";
                    default -> throw new IllegalArgumentException("KRX 지원 코드가 아닙니다.");
                };
        String key = MarketHttpClient.key(config.getApiKey());
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        for (int offset = 0; offset < config.getLookbackDays(); offset++) {
            LocalDate date = today.minusDays(offset);
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            String day = date.format(DateTimeFormatter.BASIC_ISO_DATE);
            JsonNode root =
                    http.json(
                            MarketHttpClient.query(
                                    config.getBaseUrl() + "/" + endpoint, Map.of("basDd", day)),
                            Map.of("AUTH_KEY", key),
                            deadline);
            if (root == null) throw new MarketDataException(Reason.INVALID_RESPONSE);
            JsonNode rows = root.path("OutBlock_1");
            MarketValues.require(rows.isArray());
            for (JsonNode row : rows) {
                if (!matches(code, row.path("IDX_NM").asText())) continue;
                MarketValues.require(day.equals(row.path("BAS_DD").asText()));
                var observation =
                        MarketValues.observation(row.path("CLSPRC_IDX").asText(), "pt", day, false);
                if (observation.isPresent()) return observation.get();
            }
            // 휴일/당일 미게시인 빈 응답만 이전 날짜로 이동한다. 잘못된 식별자는 숨기지 않는다.
            if (!rows.isEmpty()) throw new MarketDataException(Reason.NO_DATA);
        }
        throw new MarketDataException(Reason.NO_DATA);
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
