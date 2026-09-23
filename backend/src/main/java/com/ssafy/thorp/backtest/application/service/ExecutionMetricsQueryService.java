package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.repository.ExecutionMetricRepository;
import com.ssafy.thorp.backtest.application.result.ExecutionMetricSummary;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영자 집계 조회(spec 004 R-10, {@code GET /internal/execution-metrics}). 뷰와 같은 문장에 기간만 더한 결과다. */
@Service
public class ExecutionMetricsQueryService {

    private final ExecutionMetricRepository repository;

    public ExecutionMetricsQueryService(ExecutionMetricRepository repository) {
        this.repository = repository;
    }

    /**
     * @param executionType null 이면 모든 유형
     * @param from 포함, null 이면 무제한
     * @param to 미포함, null 이면 무제한
     */
    @Transactional(readOnly = true)
    public List<ExecutionMetricSummary> summarize(
            ExecutionType executionType, Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "from 은 to 보다 앞이어야 합니다.");
        }
        return repository.summarize(executionType, from, to);
    }
}
