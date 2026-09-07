package com.ssafy.thorp.market.presentation.controller;

import com.ssafy.thorp.common.web.response.HttpApiResponse;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = MarketController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MarketExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(MarketDataException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleMarket(MarketDataException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(HttpApiResponse.fail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<HttpApiResponse<Void>> handleUnexpected(Exception e) {
        // HTTP 라이브러리 예외에는 인증 URL이 포함될 수 있으므로 원문을 로깅하지 않는다.
        return ResponseEntity.internalServerError()
                .body(HttpApiResponse.fail(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return ResponseEntity.status(status)
                .headers(headers)
                .body(HttpApiResponse.fail(HttpStatus.valueOf(status.value()), "요청을 처리할 수 없습니다."));
    }
}
