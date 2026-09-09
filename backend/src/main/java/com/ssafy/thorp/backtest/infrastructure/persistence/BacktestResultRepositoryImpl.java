package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.port.message.BenchmarkResult;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.result.BacktestResultSummary;
import com.ssafy.thorp.backtest.application.result.BacktestSeries;
import com.ssafy.thorp.backtest.infrastructure.messaging.BacktestRabbitConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 결과 행과 거래 행을 저장하고 조회한다.
 *
 * <p>Job은 {@code getReferenceById} 프록시로 참조한다. 연관을 채우는 데 PK만 필요하므로 추가 SELECT가 없고, 없는 Job이면 flush 시점에
 * FK 위반으로 드러난다. 결과와 거래를 한 트랜잭션에 넣는 것은 호출자(서비스)의 책임이다.
 *
 * <p><b>JSON 파싱은 여기서 끝낸다.</b> 조회 쿼리가 JSON 컬럼을 문자열로 받아 오므로 이 클래스가 계약 DTO로 되돌려 application 계층에 넘긴다.
 * application에는 {@code ObjectMapper}도 SQL도 보이지 않는다 — 계층 규칙상 application이 infrastructure를 참조할 수
 * 없고(ArchUnit), 파싱은 저장 형식을 아는 쪽의 일이다.
 *
 * <p>파싱에 쓰는 매퍼는 <b>저장에 쓴 것과 같아야 한다.</b> {@code MessageJsonPersistenceConfig}가 Hibernate JSON 매퍼를
 * 메시지 ObjectMapper로 바꿔 decimal을 문자열로 저장했으므로, 그 매퍼로 읽어야 값과 scale이 복원된다.
 */
@Repository
public class BacktestResultRepositoryImpl implements BacktestResultRepository {

    private static final TypeReference<List<EngineOutput.PortfolioSnapshot>> SERIES =
            new TypeReference<>() {};

    private final ObjectMapper mapper = BacktestRabbitConfig.messageObjectMapper();

    private final BacktestJobJpaRepository jobRepository;
    private final BacktestResultJpaRepository resultRepository;
    private final BacktestTradeJpaRepository tradeRepository;

    public BacktestResultRepositoryImpl(
            BacktestJobJpaRepository jobRepository,
            BacktestResultJpaRepository resultRepository,
            BacktestTradeJpaRepository tradeRepository) {
        this.jobRepository = jobRepository;
        this.resultRepository = resultRepository;
        this.tradeRepository = tradeRepository;
    }

    @Override
    public void save(SaveBacktestResultCommand command) {
        BacktestJobJpaEntity job = jobRepository.getReferenceById(command.backtestJobId());
        resultRepository.save(BacktestResultJpaEntity.from(job, command));
        tradeRepository.saveAll(
                command.engineOutput().trades().stream()
                        .map(trade -> BacktestTradeJpaEntity.from(job, trade))
                        .toList());
    }

    @Override
    public Optional<BacktestResultSummary> findSummary(Long backtestJobId) {
        return resultRepository
                .findSummaryRow(backtestJobId)
                .map(
                        row ->
                                new BacktestResultSummary(
                                        row.getErrorCode(),
                                        row.getErrorMessage(),
                                        row.getStartedAt(),
                                        row.getFinishedAt(),
                                        read(
                                                row.getPerformance(),
                                                EngineOutput.PerformanceResult.class),
                                        read(
                                                row.getExecution(),
                                                EngineOutput.ExecutionResult.class),
                                        row.getBenchmarkInstrumentId(),
                                        decimal(row.getBenchmarkTotalReturn())));
    }

    @Override
    public Optional<BacktestSeries> findTimeseries(Long backtestJobId) {
        return resultRepository
                .findSeriesRow(backtestJobId)
                .map(
                        row ->
                                new BacktestSeries(
                                        read(row.getPortfolioSeries(), SERIES),
                                        read(row.getBenchmark(), BenchmarkResult.class)));
    }

    @Override
    public Optional<List<EngineOutput.PortfolioSnapshot>> findPortfolioSeries(Long backtestJobId) {
        return resultRepository
                .findPortfolioSeriesJson(backtestJobId)
                .map(json -> read(json, SERIES));
    }

    /** {@code jsonb}에 문자열로 저장된 decimal을 값과 scale 그대로 되돌린다. */
    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private <T> T read(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("결과 JSON을 읽을 수 없습니다: " + type.getType(), e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null) {
            return null; // 실패한 실행은 performance·execution이 없다
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            // 우리가 저장한 JSON을 우리가 못 읽는 상황이다. 데이터 손상이거나 매퍼 설정이 어긋난 것이므로
            // 빈 값으로 넘기지 않는다 — 조회가 조용히 null 지표를 돌려주면 원인을 찾을 수 없다.
            throw new IllegalStateException("결과 JSON을 읽을 수 없습니다: type=" + type.getSimpleName(), e);
        }
    }
}
