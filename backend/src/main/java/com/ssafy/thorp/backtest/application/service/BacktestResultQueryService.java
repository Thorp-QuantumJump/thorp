package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.port.message.BenchmarkResult;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestResultSummary;
import com.ssafy.thorp.backtest.application.result.BacktestSeries;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestFailedException;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.exception.BacktestResultNotReadyException;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
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
        if (isBenchmarkMismatch(job, summary)) {
            log.warn(
                    "요청과 결과의 벤치마크가 다르다 (워커가 다른 종목으로 계산했다): backtestId={} 요청={} 결과={}",
                    backtestId,
                    job.getBenchmarkInstrumentId().value(),
                    summary.benchmarkInstrumentId());
        }
        return BacktestDetailResult.of(job, ExecutionPolicy.CURRENCY, summary);
    }

    /**
     * 요청한 벤치마크와 결과의 벤치마크가 다른가.
     *
     * <p>결과에 벤치마크가 없는 것은 <b>불일치가 아니다</b> — 워커에 계산기가 없어 현재는 그게 정상이고, 매 요청마다 경고를 남기면 로그만 시끄러워진다. 결과에
     * 값이 있는데 요청과 다를 때만 참이다. 그 응답은 {@code config.benchmarkId}와 {@code
     * summary.benchmark.instrumentId}가 서로 다른 모순된 상태이므로 조용히 내보내지 않는다.
     */
    static boolean isBenchmarkMismatch(BacktestJob job, BacktestResultSummary summary) {
        if (summary.benchmarkInstrumentId() == null) {
            return false;
        }
        String requested =
                job.getBenchmarkInstrumentId() == null
                        ? null
                        : job.getBenchmarkInstrumentId().value();
        return !summary.benchmarkInstrumentId().equals(requested);
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

    /**
     * 거래 내역 한 페이지. 거래가 0건이면 빈 {@code content}와 함께 정상 응답이다 — 결과가 있는데 거래가 없었던 실행은 실제로 가능하다. "아직 결과가
     * 없음"은 관문이 409로 걸렀다.
     *
     * <p><b>결과 행 존재를 따로 확인한다.</b> 이 조회는 {@code backtest_trade}만 읽어서 결과 행 유실을 스스로 알아채지 못한다 — 거래가
     * 0건이면 빈 페이지를, 거래 행만 남아 있으면 목록을 정상 응답으로 돌려주게 된다. 다른 세 엔드포인트는 결과 컬럼을 읽다가 자연히 걸리므로 여기만 명시적으로 본다.
     */
    public BacktestTradePageResult getTrades(String backtestId, FindBacktestTradesQuery query) {
        BacktestJob job = requireResultReady(backtestId);
        if (!resultRepository.exists(job.getId())) {
            throw missingResultRow(job);
        }
        Page<EngineOutput.TradeResult> page = resultRepository.findTrades(job.getId(), query);
        return new BacktestTradePageResult(
                backtestId,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getContent());
    }

    /**
     * 특정 시점 보유 구성. 스냅샷이 없는 날은 <b>요청일 이하에서 스냅샷이 있는 가장 최근 날짜</b>를 돌려준다(설계 §조회 규칙).
     *
     * <p>체결이 없던 날의 보유 종목·수량은 직전 스냅샷과 같으므로 구성 정보는 정확하다. 다만 평가액은 스냅샷 날짜의 Close 기준이라 응답에 {@code
     * snapshotDate}를 담아 화면이 표시하게 한다.
     *
     * <p>계약상 시작일·종료일에는 항상 스냅샷이 있다. 못 찾는 경우는 두 가지이고 <b>원인이 다르므로 코드도 다르다</b> — 요청일이 첫 보유 스냅샷보다 앞이면
     * 400(요청 잘못), 보유 스냅샷이 시계열 전체에 하나도 없으면 500(저장 데이터가 계약 위반). 후자를 400으로 주면 서버 데이터 손상을 클라이언트 탓으로 보이게
     * 한다.
     */
    public BacktestPositionsResult getPositions(String backtestId, LocalDate date) {
        BacktestJob job = requireResultReady(backtestId);
        List<EngineOutput.PortfolioSnapshot> series =
                require(resultRepository.findPortfolioSeries(job.getId()), job);
        EngineOutput.PortfolioSnapshot snapshot = latestSnapshotOnOrBefore(series, date, job);
        return new BacktestPositionsResult(
                backtestId,
                date,
                snapshot.date(),
                snapshot.positions().stream()
                        .map(
                                p ->
                                        new BacktestPositionsResult.Position(
                                                p.instrumentId(),
                                                p.quantity(),
                                                p.marketValue(),
                                                p.weight(),
                                                p.unrealizedPnl()))
                        .toList());
    }

    /**
     * 뒤에서부터 훑는다. 계약상 시계열은 날짜 오름차순이고 5년치라도 1,250점이라 스캔 비용이 무시할 수준이다 — SQL로 {@code
     * jsonb_array_elements} + 서브쿼리를 쓰면 읽기만 어려워진다.
     *
     * <p>{@code positions == null}은 "그날 구성 변경 없음"이라 건너뛴다. 빈 배열은 "보유 없음이 확정"이라 답이 된다.
     *
     * <p>한 번만 훑으면서 가장 이른 보유 스냅샷도 기억한다 — 못 찾았을 때 400과 500을 가르고, 400 메시지에 "언제부터 조회할 수 있는지"를 담기 위해서다.
     */
    private EngineOutput.PortfolioSnapshot latestSnapshotOnOrBefore(
            List<EngineOutput.PortfolioSnapshot> series, LocalDate date, BacktestJob job) {
        EngineOutput.PortfolioSnapshot earliest = null;
        for (int i = series.size() - 1; i >= 0; i--) {
            EngineOutput.PortfolioSnapshot snapshot = series.get(i);
            if (snapshot.positions() == null) {
                continue;
            }
            earliest = snapshot; // 역순이므로 루프가 끝나면 가장 이른 보유 스냅샷이 남는다
            if (!snapshot.date().isAfter(date)) {
                return snapshot;
            }
        }
        if (earliest == null) {
            log.error(
                    "보유 스냅샷이 하나도 없는 결과 (계약 위반 — 시작일·종료일에는 항상 있어야 한다):" + " backtestId={} 시계열={}점",
                    job.getBacktestId().value(),
                    series.size());
            throw new IllegalStateException(
                    "보유 스냅샷이 없습니다: backtestId=" + job.getBacktestId().value());
        }
        throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "요청일이 첫 보유 스냅샷보다 앞입니다: date=" + date + ", 첫 스냅샷=" + earliest.date());
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
