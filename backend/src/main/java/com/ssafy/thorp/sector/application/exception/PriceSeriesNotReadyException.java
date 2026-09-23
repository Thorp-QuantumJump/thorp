package com.ssafy.thorp.sector.application.exception;

/**
 * 섹터도 대표 ETF도 있는데 그 종목의 일별 가격이 아직 적재되지 않았다.
 *
 * <p><b>빈 선을 만들어 내지 않는다.</b> 점 없는 그래프를 내보내면 화면은 "값이 없다"가 아니라 "움직임이 없었다"로 그리게 된다(원칙 VI). 파이프라인이 적재하면
 * 달라지는 상태라 409로 나간다.
 */
public class PriceSeriesNotReadyException extends RuntimeException {

    public PriceSeriesNotReadyException(String sectorCode) {
        super("섹터 " + sectorCode + "의 가격 자료가 아직 준비되지 않았다");
    }
}
