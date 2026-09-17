package com.ssafy.thorp.result.presentation.response;

import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.presentation.response.BacktestDetailResponse;
import com.ssafy.thorp.result.application.result.PublicResultDetail;
import com.ssafy.thorp.result.application.result.ValidationTier;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /results/{publicToken}} 응답(contracts/result-api.md). 예시는 {@code
 * docs/specs/result-api/examples/result-detail-*.json}이고 {@code ResultApiIT}가 대조한다.
 *
 * <p>요약·진행·실패는 004 응답의 모양을 그대로 쓴다({@link BacktestDetailResponse}의 중첩 타입). 공개 경로가 같은 값을 다른 모양으로 내면
 * 화면이 두 벌로 읽어야 한다. {@code backtestId}·{@code runId}·{@code worker} 칸은 없다. {@code config}는 요청 사본이라
 * 요청자가 원래 없다({@code RequestSpecCodec}).
 */
public record PublicResultDetailResponse(
        String publicToken,
        RunStatus status,
        ResultSource resultSource,
        boolean partial,
        boolean cancelRequested,
        Instant expiresAt,
        BacktestDetailResponse.Progress progress,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Long queueWaitSeconds,
        Map<String, Object> config,
        Map<String, Object> versions,
        BacktestDetailResponse.Summary summary,
        String summaryUnavailable,
        BacktestDetailResponse.Failure failure,
        ValidationTier validationTier,
        String strategyName,
        WeightsSource weightsSource,
        List<String> exceptionKinds) {

    public static PublicResultDetailResponse from(PublicResultDetail result) {
        return new PublicResultDetailResponse(
                result.publicToken(),
                result.status(),
                result.resultSource(),
                result.partial(),
                result.cancelRequested(),
                result.expiresAt(),
                result.progress() == null
                        ? null
                        : new BacktestDetailResponse.Progress(
                                result.progress().stage(),
                                result.progress().lastTradingDate(),
                                result.progress().completedTradingDays(),
                                result.progress().totalTradingDays()),
                result.queuedAt(),
                result.startedAt(),
                result.finishedAt(),
                result.queueWaitSeconds(),
                result.config(),
                result.versions(),
                result.summary() == null ? null : BacktestDetailResponse.summary(result.summary()),
                result.summaryUnavailable(),
                result.failure() == null
                        ? null
                        : new BacktestDetailResponse.Failure(
                                result.failure().code(), result.failure().message()),
                result.validationTier(),
                result.strategyName(),
                result.weightsSource(),
                result.exceptionKinds());
    }
}
