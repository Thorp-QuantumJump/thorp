package com.ssafy.thorp.macro.presentation.controller;

import com.ssafy.thorp.common.web.response.HttpApiResponse;
import com.ssafy.thorp.macro.application.exception.MacroRegistryNotPublishedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 008 조회의 실패 응답을 계약의 봉투로 맞춘다 (contracts/openapi.yaml ErrorEnvelope).
 *
 * <p>계약이 정의하는 실패는 500 하나뿐이다. 자료 없음·수집 실패는 200 으로 나가므로 여기로 오지 않는다.
 *
 * <p><b>{@code @Order} 가 없으면 이 핸들러가 밀린다.</b> {@code GlobalExceptionHandler} 도 같은 예외를 잡는데 그쪽은 구형
 * {@code ApiResponse} 봉투({@code success/data/error})를 쓴다. 우선순위를 주지 않으면 계약이 정한 봉투 대신 그것이 나가고, 그 차이는
 * 컨트롤러를 지나는 요청에서만 드러난다 — 서비스를 직접 부르는 테스트로는 보이지 않는다.
 */
@RestControllerAdvice(assignableTypes = MacroSnapshotController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MacroSnapshotExceptionHandler {

    @ExceptionHandler(MacroRegistryNotPublishedException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleRegistryNotPublished(
            MacroRegistryNotPublishedException error) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(HttpApiResponse.fail(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage()));
    }
}
