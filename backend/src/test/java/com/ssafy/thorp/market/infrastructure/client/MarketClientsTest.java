package com.ssafy.thorp.market.infrastructure.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.market.application.port.MarketDeadline;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.exception.MarketDataException.Reason;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import com.ssafy.thorp.market.infrastructure.config.MarketApiProperties;
import java.net.URI;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarketClientsTest {
    private final MarketHttpClient http = mock(MarketHttpClient.class);
    private final MarketApiProperties config = new MarketApiProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);

    private MarketDeadline deadline() {
        return new MarketDeadline(Duration.ofSeconds(8));
    }

    @Test
    void ecosUsesLatestValidObservationAndPreservesMonthlyUnit() throws Exception {
        config.getEcos().setApiKey("test-key");
        when(http.json(any(), anyMap(), any()))
                .thenReturn(
                        mapper.readTree(
                                """
            {"StatisticSearch":{"list_total_count":3,"row":[
             {"STAT_CODE":"161Y006","ITEM_CODE1":"BBHA00","UNIT_NAME":"십억원","TIME":"202605","DATA_VALUE":"4,000"},
             {"STAT_CODE":"161Y006","ITEM_CODE1":"BBHA00","UNIT_NAME":"십억원","TIME":"202607","DATA_VALUE":"-"},
             {"STAT_CODE":"161Y006","ITEM_CODE1":"BBHA00","UNIT_NAME":"십억원","TIME":"202606","DATA_VALUE":"4,100.5"}]}}
            """));
        var result = new EcosMarketClient(http, config, clock).fetch(MarketCode.M2, deadline());
        assertThat(result.value()).isEqualByComparingTo("4100.5");
        assertThat(result.observationDate()).isEqualTo("2026-06");
        assertThat(result.unit()).isEqualTo("십억원");
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(http).json(uri.capture(), anyMap(), any());
        assertThat(uri.getValue().getPath()).contains("/161Y006/M/").endsWith("/BBHA00");
    }

    @Test
    void ecosRejectsWrongUnitAndHttp200ErrorBody() throws Exception {
        config.getEcos().setApiKey("test-key");
        when(http.json(any(), anyMap(), any()))
                .thenReturn(
                        mapper.readTree(
                                """
            {"StatisticSearch":{"list_total_count":1,"row":[
             {"STAT_CODE":"731Y001","ITEM_CODE1":"0000001","UNIT_NAME":"%","TIME":"20260904","DATA_VALUE":"3"}]}}
            """));
        var client = new EcosMarketClient(http, config, clock);
        assertThatThrownBy(() -> client.fetch(MarketCode.USD_KRW, deadline()))
                .isInstanceOf(MarketDataException.class);
        when(http.json(any(), anyMap(), any()))
                .thenReturn(
                        mapper.readTree(
                                """
{"RESULT":{"CODE":"INFO-100"}}
"""));
        assertThatThrownBy(() -> client.fetch(MarketCode.USD_KRW, deadline()))
                .isInstanceOfSatisfying(
                        MarketDataException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.AUTHENTICATION));
    }

    @Test
    void krxFindsExactIndexAndSkipsWeekendAfterEmptyMonday() throws Exception {
        config.getKrx().setApiKey("test-key");
        when(http.json(any(), anyMap(), any()))
                .thenReturn(
                        mapper.readTree(
                                """
{"OutBlock_1":[]}
"""),
                        mapper.readTree(
                                """
            {"OutBlock_1":[
              {"IDX_NM":"코스피 200","BAS_DD":"20260904","CLSPRC_IDX":"300"},
              {"IDX_NM":"코스피","BAS_DD":"20260904","CLSPRC_IDX":"2,500.2"}]}
            """));
        var result = new KrxMarketClient(http, config, clock).fetch(MarketCode.KOSPI, deadline());
        assertThat(result.value()).isEqualByComparingTo("2500.2");
        assertThat(result.observationDate()).isEqualTo("2026-09-04");
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(http, times(2)).json(uri.capture(), eq(Map.of("AUTH_KEY", "test-key")), any());
        assertThat(uri.getAllValues().get(1).getQuery()).contains("basDd=20260904");
    }

    @Test
    void kosisUsesYoyClassificationAndEncodesPadding() throws Exception {
        config.getKosis().setApiKey("ab+c/=");
        when(http.json(any(), anyMap(), any()))
                .thenReturn(
                        mapper.readTree(
                                """
            [{"ORG_ID":"101","TBL_ID":"DT_1J22042","ITM_ID":"T03","C1":"4",
              "PRD_SE":"M","PRD_DE":"202608","UNIT_NM":"%","DT":"2.1"}]
            """));
        var result =
                new KosisMarketClient(http, config, clock)
                        .fetch(MarketCode.CORE_CPI_YOY, deadline());
        assertThat(result.unit()).isEqualTo("% YoY");
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(http).json(uri.capture(), anyMap(), any());
        assertThat(uri.getValue().getRawQuery())
                .contains("apiKey=ab%2Bc%2F%3D", "itmId=T03", "objL1=4");
    }

    @Test
    void kosisRecognizesInvalidKeyBody() throws Exception {
        config.getKosis().setApiKey("test=");
        when(http.json(any(), anyMap(), any()))
                .thenReturn(
                        mapper.readTree(
                                """
{"err":"11","errMsg":"secret"}
"""));
        assertThatThrownBy(
                        () ->
                                new KosisMarketClient(http, config, clock)
                                        .fetch(MarketCode.COINCIDENT_CYCLE, deadline()))
                .isInstanceOfSatisfying(
                        MarketDataException.class,
                        e -> {
                            assertThat(e.reason()).isEqualTo(Reason.AUTHENTICATION);
                            assertThat(e.getMessage()).doesNotContain("secret");
                            assertThat(e.getCause()).isNull();
                        });
    }

    @Test
    void missingKeyMakesNoNetworkCall() {
        assertThatThrownBy(
                        () ->
                                new CustomsMarketClient(http, config, clock)
                                        .fetch(MarketCode.EXPORTS_USD, deadline()))
                .isInstanceOfSatisfying(
                        MarketDataException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIGURED));
        verifyNoInteractions(http);
    }

    @Test
    void customsIgnoresTotalAndUsesDollarValueWithoutScaling() {
        var result =
                CustomsMarketClient.parse(
                        """
            <response><header><resultCode>00</resultCode></header><body><items>
            <item><year>2026.07</year><expDlr>123456789</expDlr></item>
            <item><year>총계</year><expDlr>999999999</expDlr></item>
            <item><year>2026.08</year><expDlr>234567890</expDlr></item>
            </items></body></response>
            """,
                        "202509",
                        "202608");
        assertThat(result.value()).isEqualByComparingTo("234567890");
        assertThat(result.unit()).isEqualTo("USD");
        assertThat(result.observationDate()).isEqualTo("2026-08");
    }

    @Test
    void customsRejectsExternalEntities() {
        assertThatThrownBy(
                        () ->
                                CustomsMarketClient.parse(
                                        """
            <!DOCTYPE x [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <response><resultCode>00</resultCode><item><year>202608</year><expDlr>&secret;</expDlr></item></response>
            """,
                                        "202601",
                                        "202608"))
                .isInstanceOf(MarketDataException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ab+c/=", "ab%2Bc%2F%3D"})
    void customsAcceptsBothEncodedAndDecodedKeys(String key) throws Exception {
        config.getCustoms().setApiKey(key);
        when(http.text(any(), anyMap(), any()))
                .thenReturn(
                        """
            <response><header><resultCode>00</resultCode></header><body><items>
            <item><year>2026.08</year><expDlr>123</expDlr></item>
            </items></body></response>
            """);
        new CustomsMarketClient(http, config, clock).fetch(MarketCode.EXPORTS_USD, deadline());
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(http).text(uri.capture(), anyMap(), any());
        assertThat(uri.getValue().getRawQuery())
                .contains("serviceKey=ab%2Bc%2F%3D")
                .doesNotContain("%25");
    }

    @Test
    void kosisCoreCpiAllowsMissingUnitOnlyForVerifiedYoyItem() throws Exception {
        config.getKosis().setApiKey("test=");
        var data =
                mapper.readTree(
                        """
            [{"ORG_ID":"101","TBL_ID":"DT_1J22042","ITM_ID":"T03","C1":"4",
              "ITM_NM":"전년동월비(%)","C1_NM":"식료품및에너지제외지수",
              "PRD_SE":"M","PRD_DE":"202608","UNIT_NM":null,"DT":"3.4"}]
            """);
        when(http.json(any(), anyMap(), any())).thenReturn(data);
        var client = new KosisMarketClient(http, config, clock);
        var result = client.fetch(MarketCode.CORE_CPI_YOY, deadline());
        assertThat(result.value()).isEqualByComparingTo("3.4");
        assertThat(result.unit()).isEqualTo("% YoY");
        ((com.fasterxml.jackson.databind.node.ObjectNode) data.get(0)).put("ITM_NM", "전월비");
        assertThatThrownBy(() -> client.fetch(MarketCode.CORE_CPI_YOY, deadline()))
                .isInstanceOf(MarketDataException.class);
    }
}
