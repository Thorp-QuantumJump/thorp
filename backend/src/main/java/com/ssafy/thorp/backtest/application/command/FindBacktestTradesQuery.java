package com.ssafy.thorp.backtest.application.command;

import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import java.time.LocalDate;

/**
 * 거래 내역 조회 조건. 컨트롤러가 요청 DTO({@code TradePageQuery})를 검증·정규화해 만든다.
 *
 * <p>여기 오는 값은 이미 유효하다 — 범위 검증과 {@code instrumentId} 형식 검증, 기본값 적용, {@code from ≤ to} 확인이 컨트롤러에서
 * 끝난다. 서비스는 요청 DTO를 보지 않는다(계층 규칙: 서비스는 {@code command}/{@code result}만 받는다).
 *
 * <p><b>기간 필터는 진입 또는 청산이 기간 안에 있는 거래다</b>(spec 005 T015). 005 '손실이 커진 때 보기'(FR-010)가 최대 하락 구간에 일어난
 * 매매를 보이려는 것이라, 구간 전에 사서 구간 중에 판 거래도 들어와야 한다. 구간 내내 들고만 있던 거래는 들어오지 않는다 — 그 구간의 보유는 {@code
 * /positions}가 보인다. 날짜는 거래소 현지(Asia/Seoul) 기준이다(응답의 {@code entryDate}와 같다).
 *
 * @param page 0 이상
 * @param size 1~1000. 요청에서 생략하면 100
 * @param instrumentId null = 필터 없음
 * @param status null = 필터 없음
 * @param from null = 시작 제한 없음. 이 날 포함
 * @param to null = 끝 제한 없음. 이 날 포함
 */
public record FindBacktestTradesQuery(
        int page, int size, String instrumentId, TradeStatus status, LocalDate from, LocalDate to) {

    /** 기간 필터가 없는 조회. */
    public FindBacktestTradesQuery(int page, int size, String instrumentId, TradeStatus status) {
        this(page, size, instrumentId, status, null, null);
    }
}
