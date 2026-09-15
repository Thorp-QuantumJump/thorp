package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.application.command.ExecutionMetricRecord;
import com.ssafy.thorp.backtest.application.command.LimitHitRecord;
import com.ssafy.thorp.backtest.application.result.ExecutionMetricSummary;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import java.time.Instant;
import java.util.List;

/**
 * 실행 측정 기록·한도 도달 기록 저장소 포트(spec 004 R-10, T012). 구현은 {@code infrastructure.persistence}.
 *
 * <p>측정 기록은 도메인 객체가 없다 — 실행이 끝난 뒤의 관측값이라 불변식이 없고, 쓰기 한 번·집계 읽기뿐이다. 그래서 JPA 엔티티를 두지 않고 native 로 쓴다.
 */
public interface ExecutionMetricRepository {

    /**
     * 종결 한 건을 남긴다.
     *
     * @return 처음 기록했으면 true. false = 같은 실행이 이미 기록돼 있어 넘어갔다({@code run_id} UNIQUE, 늦은 중복 종결)
     */
    boolean record(ExecutionMetricRecord metric);

    void recordLimitHit(LimitHitRecord hit);

    /** 종결 시점에 RUNNING 인 <b>다른</b> 실행 수. 자기 자신은 이미 종결로 바뀌었거나 바뀌는 중이라 뺀다. */
    int countRunningExcept(long runId);

    /** {@code backtest_result} 행의 저장 크기(바이트). 결과 행이 없으면 0. */
    long resultBytes(long runId);

    /** STARTED 가 남긴 큐·워커 역할(R-20). 없으면 두 값이 null 인 레코드. */
    WorkerInfo findWorkerInfo(long runId);

    /**
     * 유형별 집계. {@code from}·{@code to}(반열림 구간, null 이면 무제한)로 {@code recorded_at}을 자른다. 둘 다 null 이면 뷰
     * {@code v_run_metric_summary}와 같은 값이어야 한다 — SQL 이 같은 문장에서 나오기 때문이다.
     *
     * @param executionType null 이면 모든 유형
     */
    List<ExecutionMetricSummary> summarize(ExecutionType executionType, Instant from, Instant to);

    record WorkerInfo(String queue, String role) {
        public static final WorkerInfo UNKNOWN = new WorkerInfo(null, null);
    }
}
