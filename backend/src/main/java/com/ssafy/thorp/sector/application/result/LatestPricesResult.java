package com.ssafy.thorp.sector.application.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /sectors/latest-prices}의 유스케이스 출력.
 *
 * <p>섹터마다 대표 ETF의 가장 늦은 거래일 수정종가와 그 직전 거래일 값이다. 화면이 가격 흐름 전체를 섹터 수만큼 받아 마지막 두 점만 쓰던 자리를 요청 한 번으로
 * 줄인다. 값을 고르는 규칙은 {@link PriceSeriesResult}와 같아서, 이 응답의 마지막 값은 그 섹터의 가격 흐름 마지막 점과 늘 같다.
 *
 * <p><b>등락률을 계산하지 않는다.</b> 직전 값을 그대로 싣고 무엇과 견줄지는 보는 쪽이 정한다. 가격 흐름이 수익률 환산을 하지 않는 것과 같은 판단이다.
 *
 * @param prices 열린 대응표의 섹터 순서를 따른다
 * @param missingSectorCodes 그 자료 버전에 가격이 한 행도 없는 섹터. 조용히 빼면 화면이 "없는 섹터"와 "아직 적재되지 않은 섹터"를 가릴 수 없다
 */
public record LatestPricesResult(
        String mapVersion,
        String datasetId,
        Instant dataDeadline,
        boolean isSample,
        List<SectorLatestPrice> prices,
        List<String> missingSectorCodes) {

    public LatestPricesResult {
        prices = List.copyOf(prices);
        missingSectorCodes = List.copyOf(missingSectorCodes);
    }

    /** 한 섹터 대표 ETF의 마지막 값. 섹터와 상품 이름을 함께 실어 화면이 목록을 다시 읽지 않게 한다. */
    public record SectorLatestPrice(
            String sectorCode, String instrumentId, String name, LatestPrice price) {}

    /**
     * 한 종목의 가장 늦은 거래일 값과 그 직전 거래일 값.
     *
     * <p><b>거래정지일과 이어 붙인 가격을 숨기지 않는다.</b> 마지막 날이 정지일이면 그 값은 직전 값을 옮겨 적은 것이다(원칙 V).
     *
     * @param previousDate 그 자료 버전에 거래일이 하루뿐이면 {@code null}. 0이나 같은 값으로 채우지 않는다 — 채우면 "변화 없음"으로 읽힌다
     * @param previousAdjustedClose {@code previousDate}와 함께 비거나 함께 찬다
     */
    public record LatestPrice(
            LocalDate date,
            BigDecimal adjustedClose,
            boolean tradingSuspended,
            boolean priceCarriedForward,
            LocalDate previousDate,
            BigDecimal previousAdjustedClose) {}

    /**
     * 저장소가 한 트랜잭션에서 만들어 돌려주는 덩어리. 자료 버전과 그 버전의 값이 따로 읽히면 서로 다른 판이 한 응답에 섞일 수 있다.
     *
     * @param byInstrument 가격이 있는 종목만 담는다
     */
    public record Snapshot(
            String datasetId,
            Instant dataDeadline,
            boolean isSample,
            Map<String, LatestPrice> byInstrument) {

        public Snapshot {
            byInstrument = Map.copyOf(byInstrument);
        }
    }
}
