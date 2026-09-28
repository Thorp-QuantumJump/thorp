package com.ssafy.thorp.sector.presentation.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import com.ssafy.thorp.sector.application.result.PriceSeriesResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /sectors/{code}/price-series} 응답 본문.
 *
 * <p>금액에는 {@code @JsonSerialize(using = PlainBigDecimalSerializer.class)}를 <b>필드마다</b> 붙인다. 웹
 * ObjectMapper에 전역 등록하면 다른 도메인의 응답에서 숫자가 문자열로 바뀌어 그 API 계약이 깨진다({@code backend/CLAUDE.md}).
 *
 * <p><b>점을 솎아 내지 않는다.</b> 화면이 작은 그림을 그린다고 서버가 미리 줄이면, 어느 날을 버렸는지 보는 쪽이 알 수 없고 다른 굵기로 보려는 화면이 원본을
 * 되찾을 수 없다. 얼마나 촘촘히 그릴지는 그리는 쪽이 정한다.
 */
public record PriceSeriesResponse(
        String instrumentId,
        String datasetId,
        Instant dataDeadline,
        boolean isSample,
        List<PricePointItem> points) {

    public static PriceSeriesResponse from(PriceSeriesResult result) {
        return new PriceSeriesResponse(
                result.instrumentId(),
                result.datasetId(),
                result.dataDeadline(),
                result.isSample(),
                result.points().stream().map(PricePointItem::from).toList());
    }

    /**
     * 하루치 한 점.
     *
     * <p>{@code tradingSuspended}와 {@code priceCarriedForward}를 함께 싣는 까닭은 그 날 값이 직전 거래일에서 옮겨 적은 것일
     * 수 있기 때문이다. 표시 없이 선만 주면 "그 값에 거래할 수 있었다"로 읽힌다(원칙 V).
     */
    public record PricePointItem(
            LocalDate date,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal adjustedClose,
            boolean tradingSuspended,
            boolean priceCarriedForward) {

        static PricePointItem from(PriceSeriesResult.PricePoint point) {
            return new PricePointItem(
                    point.date(),
                    point.adjustedClose(),
                    point.tradingSuspended(),
                    point.priceCarriedForward());
        }
    }
}
