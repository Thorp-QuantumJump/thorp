package com.ssafy.thorp.news.presentation.controller;

import com.ssafy.thorp.common.web.response.HttpApiResponse;
import com.ssafy.thorp.news.application.exception.NewsQueryException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 뉴스 조회 API의 오류 매핑(contracts/openapi.yaml 400).
 *
 * <p>여기서 다루는 것은 400 하나다. 사용자가 요청을 고쳐 다시 부를 수 있는 경우 — 한쪽만 준 기간, 역전된 기간, 상한을 넘는 기간, 해석할 수 없는 시각 표기,
 * 등록되지 않은 섹터 식별자다.
 *
 * <p><b>자료가 없는 것은 오류가 아니다.</b> 빈 결과·수집 지연·허가 대기는 200에 상태로 실린다(spec FR-014·FR-024). 그 둘을 한 곳에서 다루면
 * 외부 원천 장애가 클라이언트 오류처럼 보인다.
 *
 * <p>나머지 예외는 {@code GlobalExceptionHandler}로 흘려보낸다. catch-all을 여기 두면 전역 로깅·메시지 정책이 이 도메인에서만 달라진다.
 *
 * <p>응답 봉투는 {@code HttpApiResponse}다 — 이 도메인의 성공 응답과 같은 모양이어야 화면이 한 가지 방식으로 읽는다.
 */
@RestControllerAdvice(assignableTypes = NewsDigestController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NewsDigestExceptionHandler {

    /** 필수 파라미터가 빠졌다. 내보내기는 기간이 필수라 이 경로가 실제로 쓰인다. */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleMissingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(
                        HttpApiResponse.fail(
                                HttpStatus.BAD_REQUEST, "필수 파라미터가 빠졌습니다: " + e.getParameterName()));
    }

    /** 시각 표기를 해석할 수 없을 때 돌려주는 말. 받은 값을 되비추지 않는다(spec FR-021). */
    private static final String MALFORMED_INSTANT = "시각은 ISO-8601 형식이어야 합니다.";

    /** 개수를 정수로 읽지 못했을 때 돌려주는 말. 묶음 조회와 내보내기의 {@code limit}이 함께 쓴다. */
    private static final String MALFORMED_LIMIT = "limit은 1 이상의 정수여야 합니다.";

    @ExceptionHandler(NewsQueryException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidQuery(NewsQueryException e) {
        return ResponseEntity.badRequest()
                .body(HttpApiResponse.fail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    /**
     * {@code from}·{@code to}를 {@code Instant}로, {@code limit}을 정수로 바꾸지 못했다.
     *
     * <p>Spring이 만든 메시지에는 받은 값이 그대로 들어 있어 그대로 내보내지 않는다. 파라미터에 맞는 고정 문장으로 바꾼다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleMalformedParameter(
            MethodArgumentTypeMismatchException e) {
        String message = "limit".equals(e.getName()) ? MALFORMED_LIMIT : MALFORMED_INSTANT;
        return ResponseEntity.badRequest()
                .body(HttpApiResponse.fail(HttpStatus.BAD_REQUEST, message));
    }
}
