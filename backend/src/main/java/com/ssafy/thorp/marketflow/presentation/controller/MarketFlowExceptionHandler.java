package com.ssafy.thorp.marketflow.presentation.controller;

import com.ssafy.thorp.common.web.response.HttpApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 시장 흐름 계약의 {@link HttpApiResponse} 실패 봉투를 유지한다. */
@RestControllerAdvice(assignableTypes = MarketFlowController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MarketFlowExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(HttpApiResponse.fail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }
}
