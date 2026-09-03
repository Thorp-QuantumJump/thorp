package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestResult;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.EquityCurve;
import com.ssafy.thorp.backtest.domain.vo.EquityPoint;
import com.ssafy.thorp.backtest.domain.vo.PerformanceSummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>임시</b> dev 프로파일 시더. Worker의 결과 저장 경로가 붙기 전까지 예시 전략 3개({@code trend}, {@code dualMomentum},
 * {@code rsiMeanReversion})의 COMPLETED Job + 결과를 채워 프론트 연동을 막지 않는다.
 *
 * <p>데이터는 {@code frontend/src/data/backtestMock.ts}의 분기 20포인트를 환산한 것이다(research.md §7). {@code
 * maxDrawdown}과 낙폭 구간 3필드는 목데이터가 아니라 시계열에서 도출한다(FR-010).
 *
 * <p>멱등: {@code backtest_result}에 행이 하나라도 있으면 건너뛴다.
 *
 * <p>TODO(worker-result): Worker 결과 저장이 구현되면 이 클래스를 삭제한다. 삭제 조건을 해당 티켓 완료 조건에 포함한다.
 */
@Component
@Profile("dev")
public class DevSampleResultSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSampleResultSeeder.class);

    static final String[] STRATEGIES = {"trend", "dualMomentum", "rsiMeanReversion"};
    private static final LocalDate START = LocalDate.of(2019, 1, 1);
    private static final LocalDate END = LocalDate.of(2023, 12, 31);
    private static final long INITIAL_CASH = 10_000_000L;
    private static final String METRIC_VERSION = "1.0";

    /** 분기별 지수(시작 100). 열 순서: trend, dualMomentum, rsiMeanReversion. */
    private static final double[][] INDEX = {
        {100, 100, 100},
        {106, 102, 104},
        {112, 105, 110},
        {108, 103, 116},
        {119, 111, 111},
        {104, 98, 108},
        {126, 116, 122},
        {138, 124, 130},
        {149, 132, 137},
        {143, 127, 134},
        {164, 138, 145},
        {178, 145, 151},
        {169, 137, 145},
        {158, 129, 139},
        {176, 142, 151},
        {183, 148, 157},
        {196, 154, 162},
        {188, 149, 158},
        {210, 167, 169},
        {224.8, 184.6, 162.4},
    };

    private static final double[] TOTAL_RETURN = {1.248, 0.846, 0.624};
    private static final double[] CAGR = {0.184, 0.132, 0.108};
    private static final double[] VOLATILITY = {0.124, 0.142, 0.181};
    private static final double[] SHARPE = {1.42, 1.05, 0.78};
    private static final int[] TRADE_COUNT = {156, 89, 234};

    private final BacktestJobRepository jobRepository;
    private final BacktestResultRepository resultRepository;
    private final BacktestResultJpaRepository resultJpaRepository;

    public DevSampleResultSeeder(
            BacktestJobRepository jobRepository,
            BacktestResultRepository resultRepository,
            BacktestResultJpaRepository resultJpaRepository) {
        this.jobRepository = jobRepository;
        this.resultRepository = resultRepository;
        this.resultJpaRepository = resultJpaRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (resultJpaRepository.count() > 0) {
            log.info("[dev-seed] backtest_result에 이미 데이터가 있어 건너뜁니다");
            return;
        }
        for (int i = 0; i < STRATEGIES.length; i++) {
            BacktestJob job =
                    jobRepository.save(
                            BacktestJob.restore(
                                    null,
                                    STRATEGIES[i],
                                    new BacktestPeriod(START, END),
                                    INITIAL_CASH,
                                    BacktestStatus.COMPLETED));
            EquityCurve curve = curve(i);
            resultRepository.save(BacktestResult.create(job.getId(), summary(i, curve), curve));
            log.info("[dev-seed] {} 전략 예시 결과 저장 (jobId={})", STRATEGIES[i], job.getId());
        }
    }

    private static EquityCurve curve(int column) {
        List<EquityPoint> points = new ArrayList<>(INDEX.length);
        LocalDate date = START;
        double runningMax = 0;
        for (double[] row : INDEX) {
            double index = row[column];
            runningMax = Math.max(runningMax, index);
            points.add(
                    new EquityPoint(
                            date,
                            Math.round(index * 100_000),
                            index / 100 - 1,
                            index / runningMax - 1));
            date = date.plusMonths(3);
        }
        return new EquityCurve(points);
    }

    /** 고정 지표는 목데이터 값, 최대 낙폭과 낙폭 구간은 시계열에서 도출한다. */
    private static PerformanceSummary summary(int column, EquityCurve curve) {
        List<EquityPoint> points = curve.points();
        int trough = 0;
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).drawdown() < points.get(trough).drawdown()) {
                trough = i;
            }
        }
        double maxDrawdown = points.get(trough).drawdown();
        LocalDate peakDate = null;
        LocalDate troughDate = null;
        LocalDate recoveryDate = null;
        if (maxDrawdown < 0) {
            int peak = 0;
            for (int i = 1; i < trough; i++) {
                if (points.get(i).equity() >= points.get(peak).equity()) {
                    peak = i;
                }
            }
            peakDate = points.get(peak).date();
            troughDate = points.get(trough).date();
            for (int i = trough + 1; i < points.size(); i++) {
                if (points.get(i).equity() >= points.get(peak).equity()) {
                    recoveryDate = points.get(i).date();
                    break;
                }
            }
        }
        return new PerformanceSummary(
                curve.first().equity(),
                curve.last().equity(),
                TOTAL_RETURN[column],
                CAGR[column],
                maxDrawdown,
                peakDate,
                troughDate,
                recoveryDate,
                VOLATILITY[column],
                SHARPE[column],
                TRADE_COUNT[column],
                null,
                null,
                METRIC_VERSION);
    }
}
