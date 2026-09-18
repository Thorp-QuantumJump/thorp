package com.ssafy.thorp.result.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestExceptionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.result.BacktestWeightsResult;
import com.ssafy.thorp.backtest.application.service.BacktestResultQueryService;
import com.ssafy.thorp.backtest.application.service.BacktestRetryService;
import com.ssafy.thorp.backtest.domain.exception.BacktestResultNotReadyException;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.ProgressStage;
import com.ssafy.thorp.backtest.domain.model.PublicResultToken;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.result.application.repository.ExpiredPublicTokenRepository;
import com.ssafy.thorp.result.application.result.PublicResultDetail;
import com.ssafy.thorp.result.application.result.PublicResultRetry;
import com.ssafy.thorp.result.application.result.ValidationTier;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공개 토큰 판정과 필드 고르기(spec 005 T023). 읽기 몸통은 004 서비스라 목으로 두고 "무엇을 버리고 무엇을 더했는가"를 본다.
 *
 * <p>실제 DB·HTTP 경로는 {@code ResultApiIT}가 본다.
 */
class PublicResultServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String BACKTEST_ID = "BT-20260917-000001";

    private final BacktestRequestRepository requestRepository =
            mock(BacktestRequestRepository.class);
    private final BacktestResultQueryService queryService = mock(BacktestResultQueryService.class);
    private final BacktestRetryService retryService = mock(BacktestRetryService.class);
    private final ParameterRegistryPort registry = mock(ParameterRegistryPort.class);
    private final ExpiredPublicTokenRepository expiredTokens =
            mock(ExpiredPublicTokenRepository.class);

    private final PublicResultService service =
            new PublicResultService(
                    requestRepository,
                    queryService,
                    retryService,
                    registry,
                    expiredTokens,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private static BacktestRequest request(Long requesterId, Instant expiresAt) {
        return request(requesterId, expiresAt, ExecutionType.SINGLE);
    }

    private static BacktestRequest request(
            Long requesterId, Instant expiresAt, ExecutionType type) {
        return BacktestRequest.restore(
                1L,
                new BacktestId(BACKTEST_ID),
                11L,
                requesterId,
                type,
                ResultSource.NEW,
                null,
                null,
                null,
                false,
                PublicResultToken.of(TOKEN),
                expiresAt);
    }

    private void stored(BacktestRequest request) {
        when(requestRepository.findByPublicToken(PublicResultToken.of(TOKEN)))
                .thenReturn(Optional.of(request));
    }

    private static BacktestDetailResult detail(RunStatus status) {
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("rules", "1");
        versions.put("worker", "2.0.0");
        return new BacktestDetailResult(
                BACKTEST_ID,
                status,
                ResultSource.NEW,
                false,
                false,
                new BacktestDetailResult.Progress(
                        ProgressStage.COMPUTING, LocalDate.of(2025, 3, 31), 60, 120),
                NOW.minusSeconds(60),
                NOW.minusSeconds(57),
                null,
                3L,
                Map.of("strategyId", "EQUAL_WEIGHT"),
                versions,
                null,
                null,
                null);
    }

    @Test
    @DisplayName("형식이 틀린 토큰은 조회하지 않고 400 이다 — 받은 문자열을 문구에 되돌려 주지 않는다")
    void malformedTokenIsInvalidInput() {
        assertThatThrownBy(() -> service.getDetail("NOT-A-TOKEN"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exc -> {
                            assertThat(exc.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                            assertThat(exc.getMessage()).doesNotContain("NOT-A-TOKEN");
                        });
        verify(requestRepository, never()).findByPublicToken(any());
    }

    @Test
    @DisplayName("없는 토큰은 404 다")
    void unknownTokenIsNotFound() {
        when(requestRepository.findByPublicToken(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(TOKEN))
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName(
            "요청은 없지만 정리 작업이 남긴 묘비가 있으면 404 가 아니라 410 이다 — 묘비는 토큰 원문이 아니라 SHA-256 으로 찾는다 (T087)")
    void deletedExpiredTokenIsGoneNotNotFound() {
        when(requestRepository.findByPublicToken(any())).thenReturn(Optional.empty());
        // echo -n 0123456789abcdef0123456789abcdef | sha256sum
        when(expiredTokens.exists(
                        "3eb1bd439947eb762998e566ccc2e099c791118b2f40579cc4f7da2b5061b7f9"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.getDetail(TOKEN))
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.RESULT_EXPIRED);
    }

    @Test
    @DisplayName("비로그인 결과는 만료 시각이 되면 410 이고 하위 자원도 읽지 않는다")
    void expiredAnonymousResultIsGone() {
        stored(request(null, NOW));

        assertThatThrownBy(() -> service.getTimeseries(TOKEN))
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.RESULT_EXPIRED);
        verify(queryService, never()).getTimeseries(any(BacktestRequest.class));
    }

    @Test
    @DisplayName("로그인 결과는 만료가 없고 expiresAt 이 null 이다")
    void loggedInResultNeverExpires() {
        stored(request(7L, null));
        when(queryService.getDetail(any(BacktestRequest.class)))
                .thenReturn(detail(RunStatus.QUEUED));

        assertThat(service.getDetail(TOKEN).expiresAt()).isNull();
    }

    @Test
    @DisplayName("상세는 공개 토큰·UNVALIDATED·전략 이름을 담고 워커 보고를 버린다. 계산 중이면 진행을 담고 결과 칸은 비운다")
    void detailPicksPublicFields() {
        Instant expiresAt = NOW.plusSeconds(3600);
        stored(request(null, expiresAt));
        when(queryService.getDetail(any(BacktestRequest.class)))
                .thenReturn(detail(RunStatus.RUNNING));
        // 목을 when(...) 인자 안에서 만들면 Mockito 가 스텁 중첩으로 거절한다. 먼저 만든다.
        StrategyRegistration registration = registration("똑같이 나누기");
        when(registry.find("EQUAL_WEIGHT")).thenReturn(Optional.of(registration));

        PublicResultDetail result = service.getDetail(TOKEN);

        assertThat(result.publicToken()).isEqualTo(TOKEN);
        assertThat(result.config()).containsEntry("executionType", "SINGLE");
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
        assertThat(result.validationTier()).isEqualTo(ValidationTier.UNVALIDATED);
        assertThat(result.strategyName()).isEqualTo("똑같이 나누기");
        assertThat(result.versions()).containsOnlyKeys("rules");
        assertThat(result.progress()).isNotNull();
        assertThat(result.queueWaitSeconds()).isEqualTo(3L);
        assertThat(result.weightsSource()).isNull();
        assertThat(result.exceptionKinds()).isNull();
        verify(queryService, never()).getWeights(any(BacktestRequest.class));
    }

    @Test
    @DisplayName("종결 결과는 진행을 비우고 weightsSource 와 중복을 뺀 예외 종류를 처음 나온 순서로 담는다")
    void completedDetailCarriesWeightsSourceAndExceptionKinds() {
        stored(request(null, NOW.plusSeconds(3600)));
        when(queryService.getDetail(any(BacktestRequest.class)))
                .thenReturn(detail(RunStatus.COMPLETED));
        when(queryService.getWeights(any(BacktestRequest.class)))
                .thenReturn(
                        new BacktestWeightsResult(
                                BACKTEST_ID, false, WeightsSource.COMPUTED, List.of()));
        when(queryService.getExceptions(any(BacktestRequest.class)))
                .thenReturn(
                        new BacktestExceptionsResult(
                                BACKTEST_ID,
                                false,
                                List.of(
                                        exception("HALT_DEFERRED"),
                                        exception("DELISTED"),
                                        exception("HALT_DEFERRED"))));

        PublicResultDetail result = service.getDetail(TOKEN);

        assertThat(result.progress()).isNull();
        assertThat(result.queueWaitSeconds()).isNull();
        assertThat(result.weightsSource()).isEqualTo(WeightsSource.COMPUTED);
        assertThat(result.exceptionKinds()).containsExactly("HALT_DEFERRED", "DELISTED");
    }

    @Test
    @DisplayName("공개 결과는 원 요청의 자동 탐색 종류를 유지한다")
    void searchBundleKindIsVisibleWithoutSearchQuery() {
        stored(request(7L, null, ExecutionType.SEARCH_BUNDLE));
        when(queryService.getDetail(any(BacktestRequest.class)))
                .thenReturn(detail(RunStatus.QUEUED));

        assertThat(service.getDetail(TOKEN).config())
                .containsEntry("executionType", "SEARCH_BUNDLE");
    }

    @Test
    @DisplayName("등록표를 읽지 못해도 상세는 열리고 전략 이름만 비운다")
    void registryFailureLeavesStrategyNameEmpty() {
        stored(request(null, NOW.plusSeconds(3600)));
        when(queryService.getDetail(any(BacktestRequest.class)))
                .thenReturn(detail(RunStatus.QUEUED));
        when(registry.find(anyString())).thenThrow(new IllegalStateException("registry down"));

        assertThat(service.getDetail(TOKEN).strategyName()).isNull();
    }

    @Test
    @DisplayName("004 예외는 코드를 유지하고 backtestId 가 든 문구를 기본 문구로 바꾼다 (SC-009)")
    void conflictMessageDoesNotLeakBacktestId() {
        stored(request(null, NOW.plusSeconds(3600)));
        when(queryService.getTimeseries(any(BacktestRequest.class)))
                .thenThrow(new BacktestResultNotReadyException(BACKTEST_ID, RunStatus.RUNNING));

        assertThatThrownBy(() -> service.getTimeseries(TOKEN))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exc -> {
                            assertThat(exc.getErrorCode()).isEqualTo(ErrorCode.RESULT_NOT_READY);
                            assertThat(exc.getMessage())
                                    .isEqualTo(ErrorCode.RESULT_NOT_READY.getMessage())
                                    .doesNotContain("BT-");
                        });
    }

    @Test
    @DisplayName("429 의 details(retryAfterSeconds)는 그대로 전달한다")
    void executionLimitKeepsDetails() {
        BacktestRequest original = request(null, NOW.plusSeconds(3600));
        stored(original);
        when(retryService.retry(original, null))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.EXECUTION_LIMIT,
                                "limit for " + BACKTEST_ID,
                                Map.of("retryAfterSeconds", 30)));

        assertThatThrownBy(() -> service.retry(TOKEN, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exc -> {
                            assertThat(exc.getErrorCode()).isEqualTo(ErrorCode.EXECUTION_LIMIT);
                            assertThat(exc.getDetails()).containsEntry("retryAfterSeconds", 30);
                            assertThat(exc.getMessage()).doesNotContain("BT-");
                        });
    }

    @Test
    @DisplayName("비로그인 사용자는 단건이 아닌 요청을 다시 시도할 수 없다 — 재시도가 실행 한도를 건너뛰는 입구가 되지 않는다 (T089)")
    void anonymousCannotRetryNonSingleRequest() {
        BacktestRequest bundle =
                BacktestRequest.restore(
                        1L,
                        new BacktestId(BACKTEST_ID),
                        11L,
                        7L,
                        ExecutionType.SEARCH_BUNDLE,
                        ResultSource.NEW,
                        null,
                        null,
                        null,
                        false,
                        PublicResultToken.of(TOKEN),
                        null);
        stored(bundle);

        assertThatThrownBy(() -> service.retry(TOKEN, null))
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(retryService, never()).retry(any(BacktestRequest.class), any());
    }

    @Test
    @DisplayName("재시도는 호출한 사람의 이름으로 접수하고, 원 요청의 backtestId 대신 원 토큰을 돌려준다")
    void retryTranslatesIdentifiersToTokens() {
        BacktestRequest original = request(7L, null);
        stored(original);
        String newToken = "fedcba9876543210fedcba9876543210";
        when(retryService.retry(original, 9L))
                .thenReturn(
                        new BacktestJobResult(
                                "BT-20260917-000002",
                                RunStatus.QUEUED,
                                ResultSource.NEW,
                                null,
                                null,
                                Map.of(),
                                BACKTEST_ID,
                                newToken));

        PublicResultRetry result = service.retry(TOKEN, 9L);

        assertThat(result.publicToken()).isEqualTo(newToken);
        assertThat(result.retryOfToken()).isEqualTo(TOKEN);
        assertThat(result.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(result.toString()).doesNotContain("BT-");
    }

    private static EngineOutput.ExceptionRecord exception(String kind) {
        return new EngineOutput.ExceptionRecord(LocalDate.of(2025, 2, 3), null, kind, null, null);
    }

    private static StrategyRegistration registration(String name) {
        StrategyRegistration registration = mock(StrategyRegistration.class);
        when(registration.name()).thenReturn(name);
        return registration;
    }
}
