package com.ssafy.thorp.regime.presentation.controller;

import com.ssafy.thorp.common.web.response.HttpApiResponse;
import com.ssafy.thorp.regime.application.exception.MacroRegistryUnavailableException;
import com.ssafy.thorp.regime.application.exception.RegimeLookupFailedException;
import com.ssafy.thorp.regime.application.exception.RegimeSearchNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 경제환경 검색의 실패 봉투 (계약 §1 오류표).
 *
 * <p><b>세 가지를 서로 다른 것으로 다룬다.</b>
 *
 * <ul>
 *   <li><b>계산 불가</b>는 여기 오지 않는다 — 200 + {@code NOT_COMPUTABLE}이다. "다시 와도 같은 것"이라 오류가 아니다.
 *   <li><b>없는 것</b>(404) — 등록표에 없는 분야, 자료 범위 밖 기준월, 정리·철회로 사라진 항목.
 *   <li><b>조회 실패</b>(503) — 저장된 결과를 <b>읽지 못했다</b>. "다시 오면 달라질 수 있는 것"이라 재시도할 수 있게 알린다.
 * </ul>
 *
 * <p>계산 불가에 503을 쓰지 않는 이유가 그대로 뒤집힌다 — 503은 "일시적이니 다시 오라"는 뜻인데 척도가 0인 것은 다시 와도 같고, 읽기 실패는 정말로 다시 오면
 * 달라질 수 있다(spec FR-034, SC-010).
 */
@RestControllerAdvice(assignableTypes = RegimeSearchController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RegimeSearchExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleInvalidInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(HttpApiResponse.fail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(RegimeSearchNotFoundException.class)
    public ResponseEntity<HttpApiResponse<Void>> handleNotFound(RegimeSearchNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(HttpApiResponse.fail(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    /**
     * 조회 실패 — <b>빈 결과나 '계산 불가'로 바꾸지 않는다</b>(spec FR-034).
     *
     * <p>읽지 못한 것을 "결과가 없다"로 내보내면 실패가 정상 화면 안에 숨고, 사용자는 기다려야 할 때 포기한다.
     */
    @ExceptionHandler({RegimeLookupFailedException.class, MacroRegistryUnavailableException.class})
    public ResponseEntity<HttpApiResponse<Void>> handleLookupFailure(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(HttpApiResponse.fail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
    }
}
