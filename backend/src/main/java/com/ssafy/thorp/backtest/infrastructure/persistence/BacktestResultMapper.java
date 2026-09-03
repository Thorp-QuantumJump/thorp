package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.domain.model.BacktestResult;
import com.ssafy.thorp.backtest.domain.vo.EquityCurve;
import com.ssafy.thorp.backtest.domain.vo.EquityPoint;
import com.ssafy.thorp.backtest.domain.vo.PerformanceSummary;
import java.time.LocalDate;
import java.util.List;

/** 도메인 모델 ↔ JPA Entity 변환. {@code date} 문자열 ↔ {@code LocalDate} 변환을 포함한다. */
final class BacktestResultMapper {

    private BacktestResultMapper() {}

    static BacktestResultJpaEntity toEntity(BacktestResult result) {
        PerformanceSummary summary = result.getSummary();
        List<EquityPointJson> curve =
                result.getEquityCurve().points().stream()
                        .map(
                                point ->
                                        new EquityPointJson(
                                                point.date().toString(),
                                                point.equity(),
                                                point.cumulativeReturn(),
                                                point.drawdown()))
                        .toList();
        return new BacktestResultJpaEntity(
                result.getId(),
                result.getBacktestJobId(),
                summary.initialEquity(),
                summary.finalEquity(),
                summary.totalReturn(),
                summary.cagr(),
                summary.maxDrawdown(),
                summary.maxDrawdownPeakDate(),
                summary.maxDrawdownTroughDate(),
                summary.maxDrawdownRecoveryDate(),
                summary.volatility(),
                summary.sharpeRatio(),
                summary.tradeCount(),
                summary.winRate(),
                summary.profitFactor(),
                summary.metricDefinitionVersion(),
                curve);
    }

    /**
     * 저장된 행이 도메인 불변식을 위반하면(Worker가 잘못 쓴 행) {@link IllegalStateException}으로 바꿔 던진다. 도메인의 {@code
     * IllegalArgumentException}은 전역 핸들러가 400(클라이언트 입력 오류)으로 응답하는데, 이 경우는 서버 데이터 오류이므로 500으로 가야
     * 한다(contracts/backtest-result-table.md「백엔드 검증」).
     */
    static BacktestResult toDomain(BacktestResultJpaEntity entity) {
        try {
            return toDomainUnchecked(entity);
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new IllegalStateException(
                    "corrupt backtest_result row: id="
                            + entity.getId()
                            + ", backtestJobId="
                            + entity.getBacktestJobId(),
                    e);
        }
    }

    private static BacktestResult toDomainUnchecked(BacktestResultJpaEntity entity) {
        PerformanceSummary summary =
                new PerformanceSummary(
                        entity.getInitialEquity(),
                        entity.getFinalEquity(),
                        entity.getTotalReturn(),
                        entity.getCagr(),
                        entity.getMaxDrawdown(),
                        entity.getMaxDrawdownPeakDate(),
                        entity.getMaxDrawdownTroughDate(),
                        entity.getMaxDrawdownRecoveryDate(),
                        entity.getVolatility(),
                        entity.getSharpeRatio(),
                        entity.getTradeCount(),
                        entity.getWinRate(),
                        entity.getProfitFactor(),
                        entity.getMetricDefinitionVersion());
        EquityCurve curve =
                new EquityCurve(
                        entity.getEquityCurve().stream()
                                .map(
                                        point ->
                                                new EquityPoint(
                                                        LocalDate.parse(point.date()),
                                                        point.equity(),
                                                        point.cumulativeReturn(),
                                                        point.drawdown()))
                                .toList());
        return BacktestResult.restore(entity.getId(), entity.getBacktestJobId(), summary, curve);
    }
}
