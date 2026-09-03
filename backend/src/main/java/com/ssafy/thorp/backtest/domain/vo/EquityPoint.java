package com.ssafy.thorp.backtest.domain.vo;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 특정 거래일의 총 자산 가치 시점.
 *
 * @param date 거래일
 * @param equity 종가 기준 총 자산(KRW 정수), 0 이상
 * @param cumulativeReturn 시작일 대비 누적 수익률({@code equity / equity[0] - 1})
 * @param drawdown 직전 최고점 대비 하락률({@code equity / max(equity[0..t]) - 1}), 0 또는 음수
 */
public record EquityPoint(LocalDate date, long equity, double cumulativeReturn, double drawdown) {

    public EquityPoint {
        Objects.requireNonNull(date, "date must not be null");
        if (equity < 0) {
            throw new IllegalArgumentException("equity must not be negative: " + equity);
        }
        if (!Double.isFinite(cumulativeReturn)) {
            throw new IllegalArgumentException(
                    "cumulativeReturn must be finite: " + cumulativeReturn);
        }
        if (!Double.isFinite(drawdown) || drawdown > 0) {
            throw new IllegalArgumentException("drawdown must be zero or negative: " + drawdown);
        }
    }
}
