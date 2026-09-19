package com.ssafy.thorp.result.application.service;

import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestExceptionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import com.ssafy.thorp.backtest.application.result.BacktestWeightsResult;
import com.ssafy.thorp.backtest.application.service.BacktestResultQueryService;
import com.ssafy.thorp.backtest.application.service.BacktestRetryService;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PublicResultToken;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.result.application.repository.ExpiredPublicTokenRepository;
import com.ssafy.thorp.result.application.result.PublicResultDetail;
import com.ssafy.thorp.result.application.result.PublicResultIndexComparison;
import com.ssafy.thorp.result.application.result.PublicResultRetry;
import com.ssafy.thorp.result.application.result.ValidationTier;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 공개 토큰으로 004의 결과를 읽는다(spec 005 US1, contracts/result-api.md).
 *
 * <p><b>판정은 여기서, 읽기는 004에서.</b> 토큰 형식(400)·존재(404)·만료(410)를 {@link #resolve}가 끝내고, 결과를 읽는 몸통은 004의
 * 요청 기준 오버로드({@link BacktestResultQueryService#getDetail(BacktestRequest)} 등)를 그대로 쓴다. 상태별 409·500
 * 규칙이 소유자 경로와 한 벌이어야 두 경로가 같은 결과를 다르게 말하지 않는다.
 *
 * <p><b>004 예외의 문구를 공개 경로로 내보내지 않는다.</b> 004 예외 메시지에는 {@code backtestId=BT-…}가 들어 있고 전역 처리기는 메시지를
 * 응답에 싣는다. 그대로 두면 409 본문 하나로 SC-009가 깨진다. 코드와 {@code details}는 유지하고 문구만 코드의 기본 문구로 바꾼다({@link
 * #publicly}).
 *
 * <p>404와 410을 가르는 근거는 계약 "오류"에 있다 — 토큰이 128비트 난수라 존재 여부를 훑을 수 없고, 사용자에게는 "사라졌다"와 "잘못된 주소"가 다른 안내다.
 */
@Service
public class PublicResultService {

    private static final Logger log = LoggerFactory.getLogger(PublicResultService.class);
    private static final String INDEX_SERIES_UNAVAILABLE = "INDEX_SERIES_UNAVAILABLE";

    private final BacktestRequestRepository requestRepository;
    private final BacktestResultQueryService queryService;
    private final BacktestRetryService retryService;
    private final ParameterRegistryPort registry;
    private final ExpiredPublicTokenRepository expiredTokens;
    private final Clock clock;

    public PublicResultService(
            BacktestRequestRepository requestRepository,
            BacktestResultQueryService queryService,
            BacktestRetryService retryService,
            ParameterRegistryPort registry,
            ExpiredPublicTokenRepository expiredTokens,
            Clock clock) {
        this.requestRepository = requestRepository;
        this.queryService = queryService;
        this.retryService = retryService;
        this.registry = registry;
        this.expiredTokens = expiredTokens;
        this.clock = clock;
    }

    /**
     * 토큰을 요청으로 바꾼다. 순서가 곧 응답 코드의 우선순위다: 형식 → 존재 → 만료.
     *
     * <p>형식이 틀리면 조회하지 않는다. 로그인 사용자의 결과는 만료가 없다({@link BacktestRequest#isPublicExpired}가 false).
     *
     * <p><b>요청이 없어도 묘비가 있으면 410이다</b>(T087). 정리 작업이 만료된 요청 행을 지우면서 토큰 해시를 남기므로, 지운 뒤에 들어온 주소도 "잘못된
     * 주소"가 아니라 "결과가 남아 있지 않다"로 안내된다. 묘비의 보관 기한이 지나면 404다.
     */
    public BacktestRequest resolve(String token) {
        PublicResultToken parsed;
        try {
            parsed = PublicResultToken.of(token);
        } catch (IllegalArgumentException exc) {
            // 값 타입의 메시지에는 입력이 그대로 들어 있다. 받은 문자열을 되돌려 줄 이유가 없다.
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        PublicResultToken found = parsed;
        BacktestRequest request =
                requestRepository
                        .findByPublicToken(found)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                expiredTokens.exists(PublicTokenDigest.of(found))
                                                        ? ErrorCode.RESULT_EXPIRED
                                                        : ErrorCode.RESOURCE_NOT_FOUND));
        if (request.isPublicExpired(clock.instant())) {
            throw new BusinessException(ErrorCode.RESULT_EXPIRED);
        }
        return request;
    }

    /**
     * 상태·요약·조건. 004 상세에서 공개할 필드만 옮기고 {@code validationTier}·{@code strategyName}·{@code
     * weightsSource}·{@code exceptionKinds}를 더한다.
     *
     * <p>마지막 둘은 첫 화면이 {@code /weights}·{@code /exceptions}를 따로 부르지 않게 하려고 싣는다(SC-001의 요청 수, R-12).
     */
    public PublicResultDetail getDetail(String token) {
        BacktestRequest request = resolve(token);
        BacktestDetailResult detail = publicly(() -> queryService.getDetail(request));
        Map<String, Object> publicConfig = new LinkedHashMap<>(detail.config());
        // 실행 사본에는 요청 종류가 없다. 비교 주소의 search 인자가 없어도 탐색 후보를 구분한다.
        publicConfig.put("executionType", request.getExecutionType().name());
        boolean inFlight =
                detail.status() == RunStatus.QUEUED || detail.status() == RunStatus.RUNNING;
        boolean resultVisible =
                detail.status() == RunStatus.COMPLETED || detail.status() == RunStatus.CANCELLED;
        return new PublicResultDetail(
                request.getPublicToken().value(),
                detail.status(),
                detail.resultSource(),
                detail.partial(),
                detail.cancelRequested(),
                request.getPublicTokenExpiresAt(),
                inFlight ? detail.progress() : null,
                detail.queuedAt(),
                detail.startedAt(),
                detail.finishedAt(),
                inFlight ? detail.queueWaitSeconds() : null,
                publicConfig,
                withoutWorkerReport(detail.versions()),
                detail.summary(),
                detail.summaryUnavailable(),
                detail.failure(),
                ValidationTier.UNVALIDATED,
                strategyName(detail.config()),
                resultVisible ? weightsSource(request) : null,
                resultVisible ? exceptionKinds(request) : null);
    }

    public BacktestTimeseriesResult getTimeseries(String token) {
        BacktestRequest request = resolve(token);
        return publicly(() -> queryService.getTimeseries(request));
    }

    public BacktestTradePageResult getTrades(String token, FindBacktestTradesQuery query) {
        BacktestRequest request = resolve(token);
        return publicly(() -> queryService.getTrades(request, query));
    }

    public BacktestPositionsResult getPositions(String token, LocalDate date) {
        BacktestRequest request = resolve(token);
        return publicly(() -> queryService.getPositions(request, date));
    }

    public BacktestWeightsResult getWeights(String token) {
        BacktestRequest request = resolve(token);
        return publicly(() -> queryService.getWeights(request));
    }

    public BacktestExceptionsResult getExceptions(String token) {
        BacktestRequest request = resolve(token);
        return publicly(() -> queryService.getExceptions(request));
    }

    /**
     * KOSPI 보조 비교 계열.
     *
     * <p>토큰의 형식·존재·만료는 다른 공개 자원과 똑같이 먼저 판정한다. 현재 001은 최신 KOSPI 관측치만 제공하고 기간 계열 조회 포트는 제공하지 않는다. 한
     * 점이나 다른 계열을 KOSPI처럼 꾸미지 않고, 화면이 이유와 함께 비활성 상태를 보이도록 명시적으로 unavailable을 반환한다. 포트가 생기면 {@code
     * config.effectiveStart~effectiveEnd}를 조회해 {@code config.initialCash}로 서버에서 환산한다(T087).
     */
    public PublicResultIndexComparison getIndexComparison(String token) {
        resolve(token);
        return PublicResultIndexComparison.unavailable(INDEX_SERIES_UNAVAILABLE);
    }

    /**
     * 실패·취소한 결과를 같은 조건으로 다시 요청한다. 004 재시도를 그대로 타므로 동일 버전 합류·재사용·실행 한도(429)도 똑같이 받는다.
     *
     * <p><b>비로그인 재시도는 단건 요청만 받는다</b>(T089, 원칙 IX). 재시도는 원 요청의 {@code executionType}을 물려받는데 실행 한도는
     * 단건 큐에만 있어, 묶음 요청의 공개 주소로 비로그인 사용자가 한도 없이 다시 접수할 수 있게 된다. 비로그인 {@code POST /backtests}와 같은
     * 규칙이다.
     *
     * @param requesterId 지금 다시 시도하는 사람. null = 비로그인. 원 요청의 요청자를 물려받지 않는다 — 공개 주소는 남에게 건네질 수 있다
     */
    public PublicResultRetry retry(String token, Long requesterId) {
        BacktestRequest original = resolve(token);
        if (requesterId == null && original.getExecutionType() != ExecutionType.SINGLE) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, "로그인하지 않은 요청은 단건 분석(SINGLE)만 다시 시도할 수 있습니다.");
        }
        BacktestJobResult retried = publicly(() -> retryService.retry(original, requesterId));
        return new PublicResultRetry(
                retried.publicToken(),
                retried.status(),
                retried.resultSource(),
                original.getPublicToken().value());
    }

    /**
     * 004 호출을 감싸 예외 문구를 기본 문구로 바꾼다. 코드·HTTP 상태·{@code details}(예: 429의 {@code retryAfterSeconds})는
     * 그대로다.
     *
     * <p>{@code BusinessException} 밖의 예외(결과 행 유실 500 등)는 전역 처리기가 이미 고정 문구로 내보내므로 건드리지 않는다.
     */
    private static <T> T publicly(Supplier<T> call) {
        try {
            return call.get();
        } catch (BusinessException exc) {
            ErrorCode code = exc.getErrorCode();
            throw new BusinessException(code, code.getMessage(), exc.getDetails());
        }
    }

    /**
     * 워커 자기 보고({@code worker})와 그 환경의 라이브러리 버전({@code dependencies}, 004 T028)은 운영 정보라 공개하지 않는다(계약
     * "버리는 필드"). 원본 맵은 004의 것이라 고치지 않고 복사한다.
     */
    private static Map<String, Object> withoutWorkerReport(Map<String, Object> versions) {
        if (versions == null
                || (!versions.containsKey("worker") && !versions.containsKey("dependencies"))) {
            return versions;
        }
        Map<String, Object> copy = new LinkedHashMap<>(versions);
        copy.remove("worker");
        copy.remove("dependencies");
        return copy;
    }

    /**
     * 등록표의 전략 이름. 비활성 전략도 이름이 나온다({@link ParameterRegistryPort#find}).
     *
     * <p><b>등록표를 읽지 못해도 결과는 연다.</b> 이름은 결과를 읽는 데 없어도 되는 보조 값이고, 등록표 장애로 이미 끝난 결과까지 503으로 막으면 사용자는
     * 자기 숫자를 볼 수 없다. 대신 null로 두어 화면이 이름을 지어내지 않게 한다(원칙 VI).
     */
    private String strategyName(Map<String, Object> config) {
        if (config == null || !(config.get("strategyId") instanceof String strategyId)) {
            return null;
        }
        try {
            return registry.find(strategyId).map(StrategyRegistration::name).orElse(null);
        } catch (RuntimeException exc) {
            log.warn(
                    "전략 등록표를 읽지 못해 공개 상세의 전략 이름을 비웠다: strategyId={} cause={}",
                    strategyId,
                    exc.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 종결 상태라도 볼 결과가 없는 경우(대기 중 취소 등)가 있다. 그때 004가 내는 409는 상세의 실패가 아니라 "이 칸은 비어 있다"이므로 null로 옮긴다.
     * 500(결과 행 유실)은 삼키지 않는다.
     */
    private WeightsSource weightsSource(BacktestRequest request) {
        try {
            return queryService.getWeights(request).weightsSource();
        } catch (BusinessException exc) {
            return null;
        }
    }

    private List<String> exceptionKinds(BacktestRequest request) {
        try {
            LinkedHashSet<String> kinds = new LinkedHashSet<>();
            for (EngineOutput.ExceptionRecord item : queryService.getExceptions(request).items()) {
                kinds.add(item.kind());
            }
            return List.copyOf(kinds);
        } catch (BusinessException exc) {
            return null;
        }
    }
}
