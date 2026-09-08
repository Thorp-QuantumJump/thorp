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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class KosisMarketClient implements MarketProvider {
    private final MarketHttpClient http;
    private final MarketApiProperties.Kosis config;
    private final Clock clock;

    public KosisMarketClient(
            MarketHttpClient http,
            MarketApiProperties properties,
            @Qualifier("marketClock") Clock clock) {
        this.http = http;
        this.config = properties.getKosis();
        this.clock = clock;
    }

    @Override
    public boolean supports(MarketCode code) {
        return code == MarketCode.COINCIDENT_CYCLE
                || code == MarketCode.LEADING_CYCLE
                || code == MarketCode.CORE_CPI_YOY;
    }

    @Override
    public MarketObservation fetch(MarketCode code, MarketDeadline deadline) {
        String key = MarketHttpClient.key(config.getApiKey());
        String table =
                code == MarketCode.CORE_CPI_YOY ? config.getCoreCpiTable() : config.getCycleTable();
        String item = code == MarketCode.CORE_CPI_YOY ? "T03" : "T1";
        String classification =
                switch (code) {
                    case COINCIDENT_CYCLE -> "B03";
                    case LEADING_CYCLE -> "A03";
                    case CORE_CPI_YOY -> "4";
                    default -> throw new IllegalArgumentException("KOSIS 지원 코드가 아닙니다.");
                };
        var uri =
                MarketHttpClient.query(
                        config.getBaseUrl(),
                        Map.of(
                                "method",
                                "getList",
                                "apiKey",
                                key,
                                "format",
                                "json",
                                "jsonVD",
                                "Y",
                                "orgId",
                                "101",
                                "tblId",
                                table,
                                "itmId",
                                item,
                                "objL1",
                                classification,
                                "prdSe",
                                "M",
                                "newEstPrdCnt",
                                "3"));
        JsonNode root = http.json(uri, Map.of(), deadline);
        if (root == null) throw new MarketDataException(Reason.INVALID_RESPONSE);
        if (root.has("err")) {
            String error = root.path("err").asText();
            if (error.equals("11")) throw new MarketDataException(Reason.AUTHENTICATION);
            if (error.equals("30")) throw new MarketDataException(Reason.NO_DATA);
            throw new MarketDataException(Reason.UPSTREAM_ERROR);
        }
        MarketValues.require(root.isArray());
        String currentMonth =
                YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul")))
                        .format(DateTimeFormatter.ofPattern("uuuuMM"));
        MarketObservation latest = null;
        for (JsonNode row : root) {
            MarketValues.require(
                    "101".equals(row.path("ORG_ID").asText())
                            && table.equals(row.path("TBL_ID").asText())
                            && item.equals(row.path("ITM_ID").asText())
                            && classification.equals(row.path("C1").asText())
                            && "M".equals(row.path("PRD_SE").asText()));
            String period = row.path("PRD_DE").asText();
            MarketValues.require(period.compareTo(currentMonth) <= 0);
            if (code == MarketCode.CORE_CPI_YOY) {
                String sourceUnit = row.path("UNIT_NM").asText("");
                // 이 표의 T03은 UNIT_NM을 생략한다. 정확한 항목명·분류명으로 % YoY를 확인한다.
                MarketValues.require(
                        "%".equals(sourceUnit)
                                || (sourceUnit.isBlank()
                                        && "전년동월비(%)".equals(row.path("ITM_NM").asText())
                                        && "식료품및에너지제외지수".equals(row.path("C1_NM").asText())));
            }
            var observation =
                    MarketValues.observation(
                            row.path("DT").asText(),
                            code == MarketCode.CORE_CPI_YOY ? "% YoY" : "지수",
                            period,
                            true);
            if (observation.isPresent()
                    && (latest == null
                            || observation
                                            .get()
                                            .observationDate()
                                            .compareTo(latest.observationDate())
                                    > 0)) latest = observation.get();
        }
        if (latest == null) throw new MarketDataException(Reason.NO_DATA);
        return latest;
    }
}
