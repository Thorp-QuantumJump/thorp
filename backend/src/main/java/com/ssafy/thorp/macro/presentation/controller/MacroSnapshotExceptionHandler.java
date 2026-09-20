package com.ssafy.thorp.macro.presentation.controller;

import com.ssafy.thorp.common.web.response.HttpApiResponse;
import com.ssafy.thorp.macro.application.exception.MacroRegistryNotPublishedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 008 조회의 실패 응답을 계약의 봉투로 맞춘다 (contracts/openapi.yaml ErrorEnvelope).
 *
 * <p>계약이 정의하는 실패는 500 하나뿐이다. 자료 없음·수집 실패는 200 으로 나가므로 여기로 오지 않는다.
 */
@RestControllerAdvice(assignableTypes = MacroSnapshotController.class)
public class MacroSnapshotExceptionHandler {

    @ExceptionHandler(MacroRegistryNotPublishedException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleRegistryNotPublished(
            MacroRegistryNotPublishedException error) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(HttpApiResponse.fail(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage()));
    }
}
