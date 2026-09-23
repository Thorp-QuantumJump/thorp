package com.ssafy.thorp.sector.application.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /sectors/{code}/price-series}의 유스케이스 출력.
 *
 * <p>점들과 그 점들이 어느 자료에서 왔는지가 한 덩어리로만 뜻이 있다. 따로 읽어 조립하면 서로 다른 자료 버전의 값이 한 선에 섞일 수 있으므로 저장소가 한 트랜잭션에서
 * 이 덩어리를 만들어 돌려준다({@link InstrumentProfileResult}와 같은 판단이다).
 *
 * <p><b>가격을 가공하지 않는다.</b> 수정종가를 그대로 싣고, 정규화나 수익률 환산은 하지 않는다. 무엇을 기준으로 얼마나 올랐는지는 보는 쪽이 정할 일이고, 여기서
 * 미리 정하면 다른 기준으로 보려는 화면이 원본을 되찾을 수 없다.
 *
 * @param isSample 이 시계열을 담은 <b>자료 버전</b>이 예시 자료인지다(FR-002). 대응표의 {@code isSample}과 값이 같을 수도 다를 수도
 *     있어 각자 자기 근거를 쓴다
 */
public record PriceSeriesResult(
        String instrumentId,
        String datasetId,
        Instant dataDeadline,
        boolean isSample,
        List<PricePoint> points) {

    /**
     * 하루치 한 점.
     *
     * <p><b>거래정지일과 이어 붙인 가격을 숨기지 않는다.</b> 그 날의 수정종가는 직전 값을 옮겨 적은 것이라 "그 값에 거래할 수 있었다"는 뜻이 아니다. 화면이
     * 그 구간을 달리 그릴 수 있도록 사실을 그대로 실어 보낸다(원칙 V, VI). 표시 방법까지 정해 주지는 않는다.
     *
     * @param tradingSuspended 그 날 거래가 정지되어 있었는가
     * @param priceCarriedForward 그 날의 가격이 직전 거래일에서 이어 붙인 값인가
     */
    public record PricePoint(
            LocalDate date,
            BigDecimal adjustedClose,
            boolean tradingSuspended,
            boolean priceCarriedForward) {}
}
