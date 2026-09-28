package com.ssafy.thorp.market.infrastructure.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.market.application.port.MarketDeadline;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.exception.MarketDataException.Reason;
import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import com.ssafy.thorp.market.infrastructure.config.MarketApiProperties;
import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarketSeriesClientsTest {
    private final MarketHttpClient http = mock(MarketHttpClient.class);
    private final MarketApiProperties config = new MarketApiProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        config.getSeries().setKrxPace(Duration.ZERO);
    }

    private MarketDeadline deadline() {
        return new MarketDeadline(Duration.ofSeconds(8));
    }

    private JsonNode krxDay(String basDd, String close) throws Exception {
        return mapper.readTree(
                """
                {"OutBlock_1":[{"IDX_NM":"코스피","BAS_DD":"%s","CLSPRC_IDX":"%s"}]}
                """
                        .formatted(basDd, close));
    }

    @Test
    void krxSeriesSkipsWeekendsAndKnownDatesAndReturnsAscending() throws Exception {
        config.getKrx().setApiKey("test-key");
        // 2026-09-04(금) ~ 2026-09-09(수): 주말 이틀과 이미 모은 9/8을 뺀 9/9, 9/7, 9/4만 부른다.
        when(http.json(any(), anyMap(), any()))
                .thenReturn(
                        krxDay("20260909", "2,510"),
                        krxDay("20260907", "2,490.5"),
                        krxDay("20260904", "2,480"));
        var client = new KrxMarketClient(http, config, clock);

        List<MarketObservation> series =
                client.fetchSeries(
                        MarketCode.KOSPI,
                        LocalDate.parse("2026-09-04"),
                        LocalDate.parse("2026-09-09"),
                        Set.of("2026-09-08"),
                        deadline());

        assertThat(series)
                .extracting(MarketObservation::observationDate)
                .containsExactly("2026-09-04", "2026-09-07", "2026-09-09");
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(http, times(3)).json(uri.capture(), anyMap(), any());
        assertThat(uri.getAllValues())
                .extracting(URI::getQuery)
                .containsExactly("basDd=20260909", "basDd=20260907", "basDd=20260904");
    }

    @Test
    void krxSeriesSkipsHolidaysButReportsAuthenticationFailure() throws Exception {
        config.getKrx().setApiKey("test-key");
        when(http.json(any(), anyMap(), any()))
                .thenReturn(mapper.readTree("{\"OutBlock_1\":[]}"), krxDay("20260908", "2,500"));
        var client = new KrxMarketClient(http, config, clock);
        var series =
                client.fetchSeries(
                        MarketCode.KOSPI,
                        LocalDate.parse("2026-09-08"),
                        LocalDate.parse("2026-09-09"),
                        Set.of(),
                        deadline());
        assertThat(series)
                .extracting(MarketObservation::observationDate)
                .containsExactly("2026-09-08");

        when(http.json(any(), anyMap(), any()))
                .thenThrow(new MarketDataException(Reason.AUTHENTICATION));
        assertThatThrownBy(
                        () ->
                                client.fetchSeries(
                                        MarketCode.KOSPI,
                                        LocalDate.parse("2026-09-08"),
                                        LocalDate.parse("2026-09-09"),
                                        Set.of(),
                                        deadline()))
                .isInstanceOfSatisfying(
                        MarketDataException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.AUTHENTICATION));
    }

    @Test
    void krxSeriesStopsAtBudgetAndReturnsWhatItCollected() throws Exception {
        config.getKrx().setApiKey("test-key");
        // 9/9는 받고 9/8에서 예산이 다 된다. 9/7 이전은 부르지 않고 모은 하루만 돌려준다.
        when(http.json(any(), anyMap(), any()))
                .thenReturn(krxDay("20260909", "2,510"))
                .thenThrow(new MarketDataException(Reason.TIMEOUT));
        var client = new KrxMarketClient(http, config, clock);

        var series =
                client.fetchSeries(
                        MarketCode.KOSPI,
                        LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-09-09"),
                        Set.of(),
                        deadline());

        assertThat(series)
                .extracting(MarketObservation::observationDate)
                .containsExactly("2026-09-09");
        verify(http, times(2)).json(any(), anyMap(), any());
    }

    @Test
    void ecosSeriesReadsRangeOnceAndDropsMissingValues() throws Exception {
        config.getEcos().setApiKey("test-key");
        when(http.json(any(), anyMap(), any()))
                .thenReturn(
                        mapper.readTree(
                                """
            {"StatisticSearch":{"list_total_count":3,"row":[
             {"STAT_CODE":"731Y001","ITEM_CODE1":"0000001","UNIT_NAME":"원","TIME":"20260908","DATA_VALUE":"1,380.1"},
             {"STAT_CODE":"731Y001","ITEM_CODE1":"0000001","UNIT_NAME":"원","TIME":"20260904","DATA_VALUE":"1,371.5"},
             {"STAT_CODE":"731Y001","ITEM_CODE1":"0000001","UNIT_NAME":"원","TIME":"20260907","DATA_VALUE":"-"}]}}
            """));
        var client = new EcosMarketClient(http, config, clock);

        var series =
                client.fetchSeries(
                        MarketCode.USD_KRW,
                        LocalDate.parse("2026-06-06"),
                        LocalDate.parse("2026-09-09"),
                        Set.of("2026-09-04"),
                        deadline());

        assertThat(series)
                .extracting(MarketObservation::observationDate)
                .containsExactly("2026-09-04", "2026-09-08");
        assertThat(series.get(1).value()).isEqualByComparingTo("1380.1");
        assertThat(series.get(1).unit()).isEqualTo("원/USD");
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(http).json(uri.capture(), anyMap(), any());
        assertThat(uri.getValue().getPath()).contains("/731Y001/D/20260606/20260909/");
    }
}
