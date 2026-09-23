package com.ssafy.thorp.result.presentation.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.backtest.application.port.message.ProcessingStage;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 진행 시계열 구독이 내보내는 이벤트 한 건({@code GET /results/{token}/progress}).
 *
 * <p><b>{@code ApiResponse} 봉투를 쓰지 않는다.</b> 봉투는 요청 하나에 응답 하나인 경로의 것이고, 여기서는 한 연결에 이벤트가 수십 번 흐른다.
 * 이벤트마다 성공 여부를 다시 싣는 것은 의미가 없고, 받는 쪽은 {@code EventSource}라 봉투를 벗기는 자리가 없다.
 *
 * <p>소수는 다른 공개 응답과 같이 문자열로 내보낸다({@link PlainBigDecimalSerializer}) — 화면이 지수 표기를 만나지 않게 하려는 것이고, 최종
 * 시계열 응답과 표기가 같아야 두 곡선을 한 그래프에 이어 그릴 수 있다.
 *
 * @param completedTradingDays 이번 구간까지 처리한 거래일 수
 * @param totalTradingDays 전체 거래일 수
 * @param points 이번 구간에서 <b>새로 계산한</b> 포인트만. 앞서 보낸 것을 다시 싣지 않는다
 */
public record PublicProgressEventResponse(
        ProcessingStage stage,
        int completedTradingDays,
        int totalTradingDays,
        LocalDate lastTradingDate,
        List<Point> points) {

    /**
     * 거래일 하나의 값. 워커가 실제로 계산한 값이며, 화면이 하루씩 그리더라도 중간값을 지어내지 않는다.
     *
     * @param equity 자산 가치
     * @param cumulativeReturn 누적 수익률
     * @param drawdown 고점 대비 하락 폭
     */
    public record Point(
            LocalDate date,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal equity,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal cumulativeReturn,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal drawdown) {

        static Point from(ProgressPayload.ProgressPoint point) {
            return new Point(
                    point.date(), point.equity(), point.cumulativeReturn(), point.drawdown());
        }
    }

    public static PublicProgressEventResponse from(ProgressPayload payload) {
        return new PublicProgressEventResponse(
                payload.stage(),
                payload.completedTradingDays(),
                payload.totalTradingDays(),
                payload.lastTradingDate(),
                payload.portfolioSeries().stream().map(Point::from).toList());
    }
}
