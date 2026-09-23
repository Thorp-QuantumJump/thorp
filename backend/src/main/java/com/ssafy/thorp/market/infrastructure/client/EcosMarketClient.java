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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class EcosMarketClient implements MarketProvider, MarketSeriesProvider {
    private final MarketHttpClient http;
    private final MarketApiProperties.Ecos config;
    private final Clock clock;

    public EcosMarketClient(
            MarketHttpClient http,
            MarketApiProperties properties,
            @Qualifier("marketClock") Clock clock) {
        this.http = http;
        this.config = properties.getEcos();
        this.clock = clock;
    }

    @Override
    public boolean supports(MarketCode code) {
        return code == MarketCode.USD_KRW
                || code == MarketCode.KTB_3Y
                || code == MarketCode.ESI_CYCLE
                || code == MarketCode.M2;
    }

    @Override
    public boolean supportsSeries(MarketCode code) {
        return code == MarketCode.USD_KRW;
    }

    @Override
    public MarketObservation fetch(MarketCode code, MarketDeadline deadline) {
        Series series = series(code);
        boolean monthly = series.cycle().equals("M");
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        // 이 범위에는 일별 61건/월별 25건 이하만 존재한다. 100행으로 전체를 읽는다.
        LocalDate start = monthly ? today.minusMonths(24) : today.minusDays(60);
        List<MarketObservation> rows = rows(series, start, today, deadline);
        if (rows.isEmpty()) throw new MarketDataException(Reason.NO_DATA);
        return rows.get(rows.size() - 1);
    }

    /** 한 번의 호출로 기간을 읽는다. 날짜마다 부르는 원천과 달리 {@code known}은 쓰지 않는다. */
    @Override
    public List<MarketObservation> fetchSeries(
            MarketCode code,
            LocalDate from,
            LocalDate to,
            Set<String> known,
            MarketDeadline deadline) {
        if (!supportsSeries(code)) throw new IllegalArgumentException("ECOS 흐름 지원 코드가 아닙니다.");
        return rows(series(code), from, to, deadline);
    }

    private Series series(MarketCode code) {
        return switch (code) {
            case USD_KRW -> new Series("731Y001", "0000001", "D", "원", "원/USD");
            case KTB_3Y -> new Series("817Y002", "010200000", "D", "연%", "%");
            case ESI_CYCLE -> new Series("513Y001", "E2000", "M", "", "지수");
            case M2 -> new Series(config.getM2Table(), config.getM2Item(), "M", "십억원", "십억원");
            default -> throw new IllegalArgumentException("ECOS 지원 코드가 아닙니다.");
        };
    }

    /** 기간 안의 유효한 관측값을 날짜 오름차순으로. 결측·"-"는 빼고 0으로 채우지 않는다. */
    private List<MarketObservation> rows(
            Series series, LocalDate from, LocalDate to, MarketDeadline deadline) {
        String key = MarketHttpClient.key(config.getApiKey());
        boolean monthly = series.cycle().equals("M");
        DateTimeFormatter format = DateTimeFormatter.ofPattern(monthly ? "uuuuMM" : "uuuuMMdd");
        String start = from.format(format);
        String end = to.format(format);
        var uri =
                UriComponentsBuilder.fromUriString(config.getBaseUrl())
                        .pathSegment(
                                "StatisticSearch",
                                "{key}",
                                "json",
                                "kr",
                                "1",
                                "100",
                                series.table(),
                                series.cycle(),
                                start,
                                end,
                                series.item())
                        .encode()
                        .buildAndExpand(Map.of("key", key))
                        .toUri();
        JsonNode root = http.json(uri, Map.of(), deadline);
        if (root == null) throw new MarketDataException(Reason.INVALID_RESPONSE);
        if (root.has("RESULT")) {
            String error = root.path("RESULT").path("CODE").asText();
            if (error.equals("INFO-200")) throw new MarketDataException(Reason.NO_DATA);
            if (error.equals("INFO-100")) throw new MarketDataException(Reason.AUTHENTICATION);
            throw new MarketDataException(Reason.UPSTREAM_ERROR);
        }
        JsonNode data = root.path("StatisticSearch");
        JsonNode rows = data.path("row");
        MarketValues.require(rows.isArray() && data.path("list_total_count").asInt() <= 100);
        List<MarketObservation> observations = new ArrayList<>();
        for (JsonNode row : rows) {
            MarketValues.require(
                    series.table().equals(row.path("STAT_CODE").asText())
                            && series.item().equals(row.path("ITEM_CODE1").asText()));
            String period = row.path("TIME").asText();
            MarketValues.require(period.compareTo(start) >= 0 && period.compareTo(end) <= 0);
            if (!series.sourceUnit().isEmpty())
                MarketValues.require(series.sourceUnit().equals(row.path("UNIT_NAME").asText()));
            MarketValues.observation(
                            row.path("DATA_VALUE").asText(), series.unit(), period, monthly)
                    .ifPresent(observations::add);
        }
        observations.sort(Comparator.comparing(MarketObservation::observationDate));
        return observations;
    }

    private record Series(
            String table, String item, String cycle, String sourceUnit, String unit) {}
}
