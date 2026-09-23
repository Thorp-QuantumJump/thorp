package com.ssafy.thorp.market.application.port;

import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** 일별 지표의 기간 관측값을 원천에서 읽는다. 최근 흐름(series) API가 쓴다. */
public interface MarketSeriesProvider {
    boolean supportsSeries(MarketCode code);

    /**
     * {@code from}~{@code to} 사이의 관측값을 날짜 오름차순으로 돌려준다.
     *
     * <p>{@code known}에 든 날짜(yyyy-MM-dd)는 이미 모은 값이라 다시 부르지 않아도 된다. 날짜마다 호출하는 원천(KRX)이 매번 전체 기간을 다시
     * 부르지 않게 하려는 것이다. 한 번에 기간을 읽는 원천은 무시해도 된다.
     *
     * <p>시간 예산이 다 되면 그때까지 모은 값만 돌려줄 수 있다. 모자란 날짜는 다음 갱신이 잇는다.
     */
    List<MarketObservation> fetchSeries(
            MarketCode code,
            LocalDate from,
            LocalDate to,
            Set<String> known,
            MarketDeadline deadline);
}
