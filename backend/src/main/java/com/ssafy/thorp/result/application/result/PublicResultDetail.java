package com.ssafy.thorp.result.application.result;

import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /results/{publicToken}}의 유스케이스 출력(contracts/result-api.md).
 *
 * <p>004 상세({@link BacktestDetailResult})에서 버릴 필드를 버리고 공개 경로에만 있는 값을 더한다. <b>{@code backtestId}를 담지
 * 않는다</b>(SC-009) — 담을 칸이 없으므로 응답으로 새어 나갈 수 없다.
 *
 * @param expiresAt 비로그인 결과의 만료 시각. 로그인 사용자의 결과는 null
 * @param progress {@code QUEUED}·{@code RUNNING}일 때만 있다
 * @param queueWaitSeconds {@code QUEUED}·{@code RUNNING}일 때만 있다
 * @param versions 004 참조 버전에서 워커 자기 보고({@code worker})를 뺀 것
 * @param strategyName 003 등록표의 전략 이름. 등록표에 없거나 읽지 못하면 null — 화면이 이름을 지어내지 않는다
 * @param weightsSource 사용자 입력 비중인지 계산 비중인지(FR-014). 결과를 볼 수 없거나 엔진이 기록하지 않았으면 null
 * @param exceptionKinds 예외 기록 {@code kind}의 중복 없는 목록(처음 나온 순서). null = 결과를 볼 수 없는 상태, 빈 목록 = 예외 없음
 */
public record PublicResultDetail(
        String publicToken,
        RunStatus status,
        ResultSource resultSource,
        boolean partial,
        boolean cancelRequested,
        Instant expiresAt,
        BacktestDetailResult.Progress progress,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Long queueWaitSeconds,
        Map<String, Object> config,
        Map<String, Object> versions,
        BacktestDetailResult.Summary summary,
        String summaryUnavailable,
        BacktestDetailResult.Failure failure,
        ValidationTier validationTier,
        String strategyName,
        WeightsSource weightsSource,
        List<String> exceptionKinds) {}
