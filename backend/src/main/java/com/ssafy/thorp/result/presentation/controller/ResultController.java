package com.ssafy.thorp.result.presentation.controller;

import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.port.BacktestProgressStream;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.result.application.result.PublicProgressTarget;
import com.ssafy.thorp.result.application.service.PublicResultService;
import com.ssafy.thorp.result.presentation.request.PublicTradePageQuery;
import com.ssafy.thorp.result.presentation.response.PublicProgressEventResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultDetailResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultExceptionsResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultIndexComparisonResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultPositionsResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultRetryResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultTimeseriesResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultTradePageResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultWeightsResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 결과 공개 열람 API(spec 005, contracts/result-api.md). 계약 요약은 {@code docs/specs/result-api/README.md}다.
 *
 * <p><b>로그인하지 않아도 된다.</b> 공개 토큰을 가진 사람이 결과를 본다 — 토큰이 128비트 난수라 추측으로 열 수 없다(FR-019). {@code
 * SecurityConfig}가 {@code /auth/me} 밖을 모두 {@code permitAll}로 두므로 보안 설정을 고치지 않는다. {@code POST}의 CSRF
 * 규약은 그대로 적용된다.
 *
 * <p>004의 {@code /backtests/**}는 소유자 경로로 그대로 있다. 이 컨트롤러는 그 앞에 놓인 두 번째 입구이고 읽기 몸통은 같다.
 */
@RestController
@RequestMapping("/results")
public class ResultController {

    private final PublicResultService service;
    private final CurrentRequester currentRequester;
    private final BacktestProgressStream progressStream;
    private final int tradesPageSize;
    private final Duration progressStreamTimeout;

    /**
     * @param tradesPageSize 공개 경로 거래 내역의 기본 크기이자 상한(R-11 임시 상한, 원칙 IX). {@code ResultProperties}가
     *     비어 있으면 기동을 막는다. 그 클래스는 {@code infrastructure}라 여기서 키로 읽는다
     * @param progressStreamTimeout 진행 시계열 구독 하나를 열어 두는 상한. 위와 같은 이유로 키로 읽는다
     */
    public ResultController(
            PublicResultService service,
            CurrentRequester currentRequester,
            BacktestProgressStream progressStream,
            @Value("${thorp.result.trades-page-size}") int tradesPageSize,
            @Value("${thorp.result.progress-stream-timeout}") Duration progressStreamTimeout) {
        this.service = service;
        this.currentRequester = currentRequester;
        this.progressStream = progressStream;
        this.tradesPageSize = tradesPageSize;
        this.progressStreamTimeout = progressStreamTimeout;
    }

    /** 상태·요약·조건. 실패해도 200이다(004 상세와 같다). 형식 오류 400, 없음 404, 만료 410. */
    @GetMapping("/{token}")
    public ResponseEntity<ApiResponse<PublicResultDetailResponse>> get(@PathVariable String token) {
        return ResponseEntity.ok(
                ApiResponse.ok(PublicResultDetailResponse.from(service.getDetail(token))));
    }

    @GetMapping("/{token}/timeseries")
    public ResponseEntity<ApiResponse<PublicResultTimeseriesResponse>> getTimeseries(
            @PathVariable String token) {
        return ResponseEntity.ok(
                ApiResponse.ok(PublicResultTimeseriesResponse.from(service.getTimeseries(token))));
    }

    /**
     * 실행 중 진행 시계열 구독(spec 004 R-05). 계산이 진행되는 동안 거래일별 포인트를 흘려, 화면이 그래프를 자라게 그린다.
     *
     * <p><b>{@code ApiResponse} 봉투를 쓰지 않는다.</b> 한 연결에 이벤트가 여러 번 흐르므로 봉투를 씌울 자리가 없다. 이벤트 이름은 둘이다 —
     * {@code progress}는 {@link PublicProgressEventResponse}이고, {@code finished}는 종결 상태 이름 하나다. 토큰
     * 판정이 다른 공개 경로와 같으므로 형식 오류 400·없음 404·만료 410은 연결을 열기 전에 평소의 봉투로 나간다.
     *
     * <p><b>이미 끝난 실행이면 구독하지 않고 바로 닫는다.</b> 걸어 두면 아무것도 오지 않는 연결이 상한까지 열려 있다. 화면은 닫힌 것을 보고 최종 시계열을
     * 읽으러 간다.
     *
     * <p><b>구독 직후 실행이 끝나면 {@code finished}가 오지 않을 수 있다.</b> 상태를 읽은 순간과 구독을 건 순간 사이에 종결이 지나갈 수 있고, 그
     * 틈은 잠금으로 막지 않는다 — 막으려면 종결 경로가 구독자를 기다려야 하고 그것은 계산 쪽을 화면에 묶는 일이다. 화면은 상태 폴링을 따로 돌리므로 이 경우에도 최종
     * 결과를 놓치지 않고, 연결은 상한에서 닫힌다.
     *
     * <p>{@code X-Accel-Buffering: no}는 중간 프록시가 응답을 모아 두지 않게 하는 신호다. 배포 환경 nginx 는 {@code
     * proxy_buffering off}로 이미 끄지만, 그 앞에 다른 프록시가 붙어도 곡선이 멈추지 않게 헤더로도 알린다.
     */
    @GetMapping(value = "/{token}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(@PathVariable String token, HttpServletResponse response) {
        PublicProgressTarget target = service.getProgressTarget(token);
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(progressStreamTimeout.toMillis());
        if (target.status().isFinal()) {
            emitter.complete();
            return emitter;
        }
        BacktestProgressStream.Subscription subscription =
                progressStream.subscribe(target.runId(), new EmitterListener(emitter));
        // 어느 쪽으로 끝나든 구독을 뗀다. 두 번 닫아도 문제가 없다.
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(
                () -> {
                    subscription.close();
                    emitter.complete();
                });
        emitter.onError(error -> subscription.close());
        return emitter;
    }

    /**
     * 진행 알림을 SSE 로 옮긴다.
     *
     * <p>전달이 끊긴 연결에서는 {@code IOException} 이 난다. 그것을 {@code RuntimeException} 으로 바꿔 올리는 이유: 알림을 보내는
     * 쪽은 브로커 리스너 스레드이고, 거기서 검사 예외를 다루게 하면 이벤트 ACK 판단이 화면 사정에 엮인다. 스트림 구현은 예외를 던진 구독자만 떼어 내고 나머지에게
     * 계속 보낸다.
     */
    private static final class EmitterListener implements BacktestProgressStream.Listener {

        private final SseEmitter emitter;

        private EmitterListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onProgress(ProgressPayload payload) {
            send("progress", PublicProgressEventResponse.from(payload));
        }

        @Override
        public void onComplete(RunStatus status) {
            // 종결을 알린 뒤 닫는다. 보내지 못해도 닫는다 — 이미 끊긴 연결이다.
            try {
                send("finished", status.name());
            } finally {
                emitter.complete();
            }
        }

        private void send(String name, Object data) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
                throw new ProgressDeliveryFailedException(e);
            }
        }
    }

    /** 구독자 하나의 전달이 끊겼다. 스트림 구현이 이것을 보고 그 구독자만 뗀다. */
    private static final class ProgressDeliveryFailedException extends RuntimeException {
        private ProgressDeliveryFailedException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * 거래 내역 한 페이지. 질의는 004와 같고({@code page}·{@code size}·{@code instrumentId}·{@code status}·{@code
     * from}·{@code to}), <b>{@code size}만 공개 상한으로 자른다</b>. 상한을 넘는 값을 400으로 거절하지 않는 이유: 상한은 설정값이라
     * 화면이 그 수를 알 수 없다. 잘린 크기는 응답 {@code size}로 보인다.
     */
    @GetMapping("/{token}/trades")
    public ResponseEntity<ApiResponse<PublicResultTradePageResponse>> getTrades(
            @PathVariable String token, @Valid @ModelAttribute PublicTradePageQuery query) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        PublicResultTradePageResponse.from(
                                service.getTrades(token, toQuery(query)))));
    }

    /** 특정 날짜의 보유 구성. {@code date} 필수이고 스냅샷이 없는 날은 직전 스냅샷이다(004와 같다). */
    @GetMapping("/{token}/positions")
    public ResponseEntity<ApiResponse<PublicResultPositionsResponse>> getPositions(
            @PathVariable String token,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        PublicResultPositionsResponse.from(service.getPositions(token, date))));
    }

    @GetMapping("/{token}/weights")
    public ResponseEntity<ApiResponse<PublicResultWeightsResponse>> getWeights(
            @PathVariable String token) {
        return ResponseEntity.ok(
                ApiResponse.ok(PublicResultWeightsResponse.from(service.getWeights(token))));
    }

    @GetMapping("/{token}/exceptions")
    public ResponseEntity<ApiResponse<PublicResultExceptionsResponse>> getExceptions(
            @PathVariable String token) {
        return ResponseEntity.ok(
                ApiResponse.ok(PublicResultExceptionsResponse.from(service.getExceptions(token))));
    }

    /** KOSPI를 같은 시작 금액으로 환산한 보조 비교 계열. 기간 계열 포트가 없으면 이유와 함께 비활성이다. */
    @GetMapping("/{token}/index-comparison")
    public ResponseEntity<ApiResponse<PublicResultIndexComparisonResponse>> getIndexComparison(
            @PathVariable String token) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        PublicResultIndexComparisonResponse.from(
                                service.getIndexComparison(token))));
    }

    /** 실패·취소한 결과를 같은 조건으로 다시 요청한다. 새 요청의 요청자는 지금 호출한 사람이다(비로그인이면 없음). */
    @PostMapping("/{token}/retry")
    public ResponseEntity<ApiResponse<PublicResultRetryResponse>> retry(
            @PathVariable String token) {
        Long requesterId = currentRequester.findRequesterId().orElse(null);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        ApiResponse.ok(
                                PublicResultRetryResponse.from(service.retry(token, requesterId))));
    }

    /**
     * 004 {@code BacktestController}와 같은 검증(종목 형식 400, 뒤집힌 기간 400)에 공개 상한을 더한다. 두 규칙 모두 빈 페이지로 대신하면
     * "그 조건의 거래가 없다"는 거짓이 되어서 거절한다.
     */
    private FindBacktestTradesQuery toQuery(PublicTradePageQuery query) {
        if (query.instrumentId() != null) {
            new InstrumentId(query.instrumentId());
        }
        if (query.from() != null && query.to() != null && query.from().isAfter(query.to())) {
            throw new IllegalArgumentException("from 은 to 보다 늦을 수 없습니다");
        }
        int size = query.size() == null ? tradesPageSize : Math.min(query.size(), tradesPageSize);
        return new FindBacktestTradesQuery(
                query.pageOrDefault(),
                size,
                query.instrumentId(),
                query.status(),
                query.from(),
                query.to());
    }
}
