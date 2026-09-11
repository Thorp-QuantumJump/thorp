package com.ssafy.thorp.sector.presentation.controller;

import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.common.web.response.ErrorResponse;
import com.ssafy.thorp.sector.application.exception.SectorMapNotReadyException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 섹터 조회 API의 오류 매핑 (contracts/sector-api.md 「오류」).
 *
 * <table>
 *   <tr><th>상황</th><th>상태</th><th>코드</th></tr>
 *   <tr><td>대응표가 발행되지 않음</td><td>409</td><td>{@code SECTOR_MAP_NOT_READY}</td></tr>
 * </table>
 *
 * <p>계약의 {@code SECTOR_NOT_FOUND}(404)와 {@code INSTRUMENT_PROFILE_NOT_READY}(409)는 여기에 두지 않았다. 두
 * 코드를 쓰는 엔드포인트가 {@code GET /sectors/{code}/instrument-profile} 하나뿐이고 그것은 T013의 일이다. 던지는 곳이 없는 처리기를
 * 미리 두면 닿지 않는 코드가 된다.
 *
 * <p>이 하나만 처리한다. 나머지(코드 형식 오류 → 400, 예상 못한 예외 → 500)는 {@code GlobalExceptionHandler}로 그대로 흘려보낸다 —
 * catch-all을 여기에 두면 전역 처리기의 로깅·메시지 정책이 이 도메인에서만 달라진다.
 *
 * <p>코드 문자열을 {@code common.exception.ErrorCode} enum에 더하지 않고 {@link ErrorResponse}의 문자열 생성자로 만든다.
 * {@code common}은 어떤 도메인 패키지도 참조하지 않는 공용 계층이고({@code ArchitectureTest} 규칙 4), 도메인별 세부 코드를 공용 enum에
 * 쌓으면 도메인이 늘 때마다 공용 파일이 함께 커진다. 응답 본문 모양({@code error.code}·{@code error.message})은 계약대로 같다.
 */
@RestControllerAdvice(assignableTypes = SectorController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SectorExceptionHandler {

    static final String SECTOR_MAP_NOT_READY = "SECTOR_MAP_NOT_READY";

    /**
     * 대응표 미발행. 재시도하면 달라질 수 있는 상태라 409다 — 결과 조회가 {@code RESULT_NOT_READY}에 409를 쓰는 것과 같은 판단이다. <b>빈
     * 배열을 만들어 내지 않는다.</b>
     */
    @ExceptionHandler(SectorMapNotReadyException.class)
    public ResponseEntity<ApiResponse<Void>> handleMapNotReady(SectorMapNotReadyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(new ErrorResponse(SECTOR_MAP_NOT_READY, e.getMessage())));
    }
}
