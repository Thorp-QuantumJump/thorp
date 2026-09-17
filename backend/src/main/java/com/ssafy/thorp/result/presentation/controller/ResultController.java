package com.ssafy.thorp.result.presentation.controller;

import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.result.application.service.PublicResultService;
import com.ssafy.thorp.result.presentation.request.PublicTradePageQuery;
import com.ssafy.thorp.result.presentation.response.PublicResultDetailResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultExceptionsResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultPositionsResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultRetryResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultTimeseriesResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultTradePageResponse;
import com.ssafy.thorp.result.presentation.response.PublicResultWeightsResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private final int tradesPageSize;

    /**
     * @param tradesPageSize 공개 경로 거래 내역의 기본 크기이자 상한(R-11 임시 상한, 원칙 IX). {@code ResultProperties}가
     *     비어 있으면 기동을 막는다. 그 클래스는 {@code infrastructure}라 여기서 키로 읽는다
     */
    public ResultController(
            PublicResultService service,
            CurrentRequester currentRequester,
            @Value("${thorp.result.trades-page-size}") int tradesPageSize) {
        this.service = service;
        this.currentRequester = currentRequester;
        this.tradesPageSize = tradesPageSize;
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
