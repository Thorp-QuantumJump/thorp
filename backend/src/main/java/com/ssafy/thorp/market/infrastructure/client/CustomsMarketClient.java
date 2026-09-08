package com.ssafy.thorp.market.infrastructure.client;

import com.ssafy.thorp.market.application.port.MarketDeadline;
import com.ssafy.thorp.market.application.port.MarketProvider;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.exception.MarketDataException.Reason;
import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import com.ssafy.thorp.market.infrastructure.config.MarketApiProperties;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

@Component
public class CustomsMarketClient implements MarketProvider {
    private final MarketHttpClient http;
    private final MarketApiProperties.Customs config;
    private final Clock clock;

    public CustomsMarketClient(
            MarketHttpClient http,
            MarketApiProperties properties,
            @Qualifier("marketClock") Clock clock) {
        this.http = http;
        this.config = properties.getCustoms();
        this.clock = clock;
    }

    @Override
    public boolean supports(MarketCode code) {
        return code == MarketCode.EXPORTS_USD;
    }

    @Override
    public MarketObservation fetch(MarketCode code, MarketDeadline deadline) {
        String key = MarketHttpClient.key(config.getApiKey());
        // 공공데이터포털의 Encoding/Decoding 키를 모두 허용한다.
        // 원래 Base64의 +는 공백으로 바뀌지 않도록 보존한다.
        if (key.contains("%")) {
            try {
                key = URLDecoder.decode(key.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new MarketDataException(Reason.NOT_CONFIGURED);
            }
        }
        // 진행 중인 달의 부분 수출액은 월간 확정 자료로 표시하지 않는다.
        YearMonth end = YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).minusMonths(1);
        var formatter = DateTimeFormatter.ofPattern("uuuuMM");
        String startMonth = end.minusMonths(11).format(formatter);
        String endMonth = end.format(formatter);
        String body =
                http.text(
                        MarketHttpClient.query(
                                config.getBaseUrl(),
                                Map.of(
                                        "serviceKey",
                                        key,
                                        "strtYymm",
                                        startMonth,
                                        "endYymm",
                                        endMonth)),
                        Map.of(),
                        deadline);
        return parse(body, startMonth, endMonth);
    }

    static MarketObservation parse(String body, String startMonth, String endMonth) {
        try {
            var builder = secureDocumentBuilder();
            Element root =
                    builder.parse(new InputSource(new StringReader(body))).getDocumentElement();
            String status = field(root, "resultCode");
            if (status.isEmpty()) status = field(root, "returnReasonCode");
            if (status.equals("20") || status.equals("30") || status.equals("31"))
                throw new MarketDataException(Reason.AUTHENTICATION);
            if (!status.equals("00")) throw new MarketDataException(Reason.UPSTREAM_ERROR);
            var rows = root.getElementsByTagName("item");
            MarketObservation latest = null;
            for (int i = 0; i < rows.getLength(); i++) {
                Element row = (Element) rows.item(i);
                String period = field(row, "year").replace(".", "").replace("-", "").trim();
                if (period.equals("총계") || period.equals("합계")) continue;
                MarketValues.require(
                        period.matches("[0-9]{6}")
                                && period.compareTo(startMonth) >= 0
                                && period.compareTo(endMonth) <= 0);
                var observation =
                        MarketValues.observation(field(row, "expDlr"), "USD", period, true);
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
        } catch (MarketDataException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketDataException(Reason.INVALID_RESPONSE);
        }
    }

    private static DocumentBuilder secureDocumentBuilder() {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder;
        } catch (ParserConfigurationException | IllegalArgumentException e) {
            // 필수 보안 설정을 지원하지 않는 환경에서는 보호되지 않은 XML 파싱을 중단한다.
            throw new MarketDataException(Reason.INVALID_RESPONSE);
        }
    }

    private static String field(Element element, String name) {
        var nodes = element.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
