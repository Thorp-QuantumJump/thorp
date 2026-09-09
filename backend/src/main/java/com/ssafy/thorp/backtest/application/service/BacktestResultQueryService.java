package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.message.BenchmarkResult;
import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestResultSummary;
import com.ssafy.thorp.backtest.application.result.BacktestSeries;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestFailedException;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.exception.BacktestResultNotReadyException;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장된 백테스트 결과를 조회한다. 계산하지 않고 투영만 한다.
 *
 * <p>{@code BacktestService}(생성·발행)와 분리한 이유: 저쪽은 생성 트랜잭션이 관심사고 발행기·조립기를 의존한다. 조회는 읽기 전용 트랜잭션과 저장소
 * 둘만 필요하다.
 *
 * <p>상태별 응답 규칙은 HTTP 계약 {@code docs/specs/backtest-api/README.md} §2.1·§2.5다. 요약해서:
 *
 * <ul>
 *   <li>{@code GET /backtests/{id}}는 정상 상태 네 가지 모두 성공한다. 실패는 {@code status}·{@code error}로 전달한다
 *   <li>하위 리소스는 결과가 없으면 409다 — {@code QUEUED}·{@code RUNNING}은 재시도 가능, {@code FAILED}는 무의미. **빈 결과를
 *       만들어 내지 않는다**
 *   <li>{@code COMPLETED}·{@code FAILED}인데 결과 행이 없으면 데이터 손상이므로 {@link IllegalStateException}(→ 500)
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class BacktestResultQueryService {

    private static final Logger log = LoggerFactory.getLogger(BacktestResultQueryService.class);

    private final BacktestJobRepository jobRepository;
    private final BacktestResultRepository resultRepository;

    public BacktestResultQueryService(
            BacktestJobRepository jobRepository, BacktestResultRepository resultRepository) {
        this.jobRepository = jobRepository;
        this.resultRepository = resultRepository;
    }

    /** 상태 + 설정 + 요약 + 오류. 없는 id만 예외(404)이고 나머지는 상태에 따라 채워진다. */
    public BacktestDetailResult getDetail(String backtestId) {
        BacktestJob job = requireJob(backtestId);
        if (!job.getStatus().isFinal()) {
            return BacktestDetailResult.withoutResult(job, ExecutionPolicy.CURRENCY);
        }
        BacktestResultSummary summary = requireSummary(job);
        return BacktestDetailResult.of(job, ExecutionPolicy.CURRENCY, summary);
    }

    /**
     * 차트용 시계열. 벤치마크 누적 수익률을 <b>날짜로</b> 조인한다.
     *
     * <p>계약상 워커가 벤치마크를 같은 거래일 축으로 forward fill하지만 그것을 전제로 NPE를 내지 않는다 — 없는 날은 {@code
     * benchmarkReturn}이 null이고 응답에서 키가 생략된다. 축이 어긋나면 차트에서 벤치마크 선이 끊겨 보인다.
     */
    public BacktestTimeseriesResult getTimeseries(String backtestId) {
        BacktestJob job = requireResultReady(backtestId);
        BacktestSeries series = require(resultRepository.findTimeseries(job.getId()), job);
        Map<LocalDate, BigDecimal> benchmark = benchmarkByDate(series.benchmark());
        List<BacktestTimeseriesResult.Point> points =
                series.portfolioSeries().stream()
                        .map(
                                s ->
                                        new BacktestTimeseriesResult.Point(
                                                s.date(),
                                                s.equity(),
                                                s.cumulativeReturn(),
                                                s.drawdown(),
                                                benchmark.get(s.date())))
                        .toList();
        return new BacktestTimeseriesResult(backtestId, points);
    }

    private static Map<LocalDate, BigDecimal> benchmarkByDate(BenchmarkResult benchmark) {
        if (benchmark == null || benchmark.timeseries() == null) {
            return Map.of();
        }
        return benchmark.timeseries().stream()
                .collect(
                        Collectors.toMap(
                                BenchmarkResult.BenchmarkPoint::date,
                                BenchmarkResult.BenchmarkPoint::cumulativeReturn,
                                (first, second) -> first));
    }

    private BacktestJob requireJob(String backtestId) {
        return jobRepository
                .findByBacktestId(backtestId)
                .orElseThrow(() -> new BacktestNotFoundException(backtestId));
    }

    /**
     * 하위 리소스 세 개의 공통 관문. **판정은 결과 행 존재가 아니라 Job 상태로 한다** — 실패한 실행도 결과 행이 있으므로(오류만 채워짐) "행이 있으면
     * 200"으로 짜면 FAILED에 빈 시계열을 주게 된다.
     *
     * <p>{@code switch}에 {@code default}를 두지 않는다. 상태가 추가되면 컴파일이 깨져서 여기를 고치게 된다.
     */
    BacktestJob requireResultReady(String backtestId) {
        BacktestJob job = requireJob(backtestId);
        switch (job.getStatus()) {
            case QUEUED, RUNNING ->
                    throw new BacktestResultNotReadyException(backtestId, job.getStatus());
            case FAILED -> throw new BacktestFailedException(backtestId);
            case COMPLETED -> {}
        }
        return job;
    }

    /**
     * 최종 상태 Job의 결과 행. 없으면 T5가 한 트랜잭션으로 묶은 저장 불변식이 깨진 것이다.
     *
     * <p>{@code RESULT_NOT_READY}(409)로 돌려주면 안 된다 — 그 코드는 "기다리면 온다"는 뜻이고 최종 상태에서는 영원히 오지 않아 프론트가 무한
     * 폴링한다.
     */
    /** 관문을 통과한(=COMPLETED) Job인데 결과 컬럼이 없으면 데이터 손상이다. {@link #requireSummary}와 같은 이유. */
    private <T> T require(Optional<T> value, BacktestJob job) {
        return value.orElseThrow(() -> missingResultRow(job));
    }

    private IllegalStateException missingResultRow(BacktestJob job) {
        log.error(
                "결과 행이 없는 최종 상태 Job (저장 트랜잭션 불변식 위반): backtestId={} status={} id={}",
                job.getBacktestId().value(),
                job.getStatus(),
                job.getId());
        return new IllegalStateException("결과 행이 없습니다: backtestId=" + job.getBacktestId().value());
    }

    private BacktestResultSummary requireSummary(BacktestJob job) {
        return require(resultRepository.findSummary(job.getId()), job);
    }
}
