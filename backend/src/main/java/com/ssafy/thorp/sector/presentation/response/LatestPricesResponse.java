package com.ssafy.thorp.sector.presentation.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import com.ssafy.thorp.sector.application.result.LatestPricesResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /sectors/latest-prices} 응답 본문.
 *
 * <p>금액에는 {@link PlainBigDecimalSerializer}를 <b>필드마다</b> 붙인다. 가격 흐름 응답과 같은 까닭이다({@link
 * PriceSeriesResponse}).
 */
public record LatestPricesResponse(
        String mapVersion,
        String datasetId,
        Instant dataDeadline,
        boolean isSample,
        List<LatestPriceItem> prices,
        List<String> missingSectorCodes) {

    public static LatestPricesResponse from(LatestPricesResult result) {
        return new LatestPricesResponse(
                result.mapVersion(),
                result.datasetId(),
                result.dataDeadline(),
                result.isSample(),
                result.prices().stream().map(LatestPriceItem::from).toList(),
                result.missingSectorCodes());
    }

    /** 한 섹터의 마지막 값. 직전 값이 없으면 두 {@code previous} 필드가 함께 {@code null}이다. */
    public record LatestPriceItem(
            String sectorCode,
            String instrumentId,
            String name,
            LocalDate date,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal adjustedClose,
            boolean tradingSuspended,
            boolean priceCarriedForward,
            LocalDate previousDate,
            @JsonSerialize(using = PlainBigDecimalSerializer.class)
                    BigDecimal previousAdjustedClose) {

        static LatestPriceItem from(LatestPricesResult.SectorLatestPrice item) {
            LatestPricesResult.LatestPrice price = item.price();
            return new LatestPriceItem(
                    item.sectorCode(),
                    item.instrumentId(),
                    item.name(),
                    price.date(),
                    price.adjustedClose(),
                    price.tradingSuspended(),
                    price.priceCarriedForward(),
                    price.previousDate(),
                    price.previousAdjustedClose());
        }
    }
}
