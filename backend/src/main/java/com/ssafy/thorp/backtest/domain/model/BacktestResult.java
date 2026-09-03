package com.ssafy.thorp.backtest.domain.model;

import com.ssafy.thorp.backtest.domain.vo.EquityCurve;
import com.ssafy.thorp.backtest.domain.vo.PerformanceSummary;
import java.util.Objects;

/**
 * 성공 완료된 백테스트 Job 하나에 1:1로 대응하는 결과 분석. 순수 도메인 모델이며 JPA Entity({@code BacktestResultJpaEntity})와
 * 분리한다.
 *
 * <p>Job과 생명주기가 다르다(Job 생성 시점에는 없고 Worker가 계산을 마친 뒤 저장한다). {@code id}는 저장 전에는 null이다.
 *
 * <p>불변식(FR-007·FR-010): 시계열의 첫 포인트 자산은 {@code summary.initialEquity}, 마지막 포인트 자산은 {@code
 * summary.finalEquity}와 같아야 한다. 지표를 다시 계산해 비교하지는 않는다.
 */
public class BacktestResult {

    private final Long id;
    private final long backtestJobId;
    private final PerformanceSummary summary;
    private final EquityCurve equityCurve;

    private BacktestResult(
            Long id, long backtestJobId, PerformanceSummary summary, EquityCurve equityCurve) {
        this.id = id;
        this.backtestJobId = backtestJobId;
        this.summary = Objects.requireNonNull(summary, "summary must not be null");
        this.equityCurve = Objects.requireNonNull(equityCurve, "equityCurve must not be null");

        long firstEquity = equityCurve.first().equity();
        if (firstEquity != summary.initialEquity()) {
            throw new IllegalArgumentException(
                    "first equity point must equal initialEquity: "
                            + firstEquity
                            + " != "
                            + summary.initialEquity());
        }
        long lastEquity = equityCurve.last().equity();
        if (lastEquity != summary.finalEquity()) {
            throw new IllegalArgumentException(
                    "last equity point must equal finalEquity: "
                            + lastEquity
                            + " != "
                            + summary.finalEquity());
        }
    }

    /** 새 결과 생성(id 없음). Worker 저장 경로 또는 dev 시더가 사용한다. */
    public static BacktestResult create(
            long backtestJobId, PerformanceSummary summary, EquityCurve equityCurve) {
        return new BacktestResult(null, backtestJobId, summary, equityCurve);
    }

    /** 저장소에서 읽어온 결과를 복원한다. Worker가 쓴 행도 같은 불변식으로 검증한다. */
    public static BacktestResult restore(
            Long id, long backtestJobId, PerformanceSummary summary, EquityCurve equityCurve) {
        return new BacktestResult(id, backtestJobId, summary, equityCurve);
    }

    public Long getId() {
        return id;
    }

    public long getBacktestJobId() {
        return backtestJobId;
    }

    public PerformanceSummary getSummary() {
        return summary;
    }

    public EquityCurve getEquityCurve() {
        return equityCurve;
    }
}
