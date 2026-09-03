package com.ssafy.thorp.backtest;

import com.ssafy.thorp.backtest.domain.model.BacktestResult;
import com.ssafy.thorp.backtest.domain.vo.EquityCurve;
import com.ssafy.thorp.backtest.domain.vo.EquityPoint;
import com.ssafy.thorp.backtest.domain.vo.PerformanceSummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 결과 도메인 테스트 공용 픽스처. 값은 dev 시더의 {@code trend} 전략(프론트 목데이터 환산)과 같다.
 *
 * <p>시작 자산 10,000,000 KRW, 분기 20포인트, 최종 22,480,000 KRW(총 수익률 1.248). 최대 낙폭 구간은 2020-01-01 고점 →
 * 2020-04-01 저점 → 2020-07-01 회복.
 */
public final class BacktestResultFixtures {

    public static final long INITIAL_EQUITY = 10_000_000L;
    public static final long FINAL_EQUITY = 22_480_000L;
    public static final String METRIC_VERSION = "1.0";

    private static final double[] INDEX = {
        100, 106, 112, 108, 119, 104, 126, 138, 149, 143, 164, 178, 169, 158, 176, 183, 196, 188,
        210, 224.8
    };

    private BacktestResultFixtures() {}

    public static PerformanceSummary sampleSummary() {
        return new PerformanceSummary(
                INITIAL_EQUITY,
                FINAL_EQUITY,
                1.248,
                0.184,
                -0.1261,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 4, 1),
                LocalDate.of(2020, 7, 1),
                0.124,
                1.42,
                156,
                null,
                null,
                METRIC_VERSION);
    }

    /** 분기 20포인트. drawdown은 직전 최고점 기준으로 계산한다. */
    public static EquityCurve sampleCurve() {
        return new EquityCurve(samplePoints());
    }

    public static List<EquityPoint> samplePoints() {
        List<EquityPoint> points = new ArrayList<>();
        LocalDate date = LocalDate.of(2019, 1, 1);
        double runningMax = 0;
        for (double index : INDEX) {
            runningMax = Math.max(runningMax, index);
            points.add(
                    new EquityPoint(
                            date,
                            Math.round(index * 100_000),
                            index / 100 - 1,
                            index / runningMax - 1));
            date = date.plusMonths(3);
        }
        return points;
    }

    public static BacktestResult sampleResult(long backtestJobId) {
        return BacktestResult.create(backtestJobId, sampleSummary(), sampleCurve());
    }

    public static EquityPoint point(LocalDate date, long equity) {
        return new EquityPoint(date, equity, 0.0, 0.0);
    }
}
