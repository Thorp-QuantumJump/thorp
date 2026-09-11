package com.ssafy.thorp.sector.presentation.controller;

import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.common.web.response.ErrorResponse;
import com.ssafy.thorp.sector.application.exception.SectorMapNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    static final String SECTOR_MAP_UNUSABLE = "SECTOR_MAP_UNUSABLE";

    /** 저장된 자료의 결함을 그대로 내보내지 않는다. 클라이언트가 할 수 있는 일이 없고 원문은 로그에 남는다. */
    private static final String MAP_UNUSABLE_MESSAGE = "대응표를 읽을 수 없다. 서버 자료를 확인해야 한다";

    private static final Logger log = LoggerFactory.getLogger(SectorExceptionHandler.class);

    /**
     * 대응표 미발행. 재시도하면 달라질 수 있는 상태라 409다 — 결과 조회가 {@code RESULT_NOT_READY}에 409를 쓰는 것과 같은 판단이다. <b>빈
     * 배열을 만들어 내지 않는다.</b>
     */
    @ExceptionHandler(SectorMapNotReadyException.class)
    public ResponseEntity<ApiResponse<Void>> handleMapNotReady(SectorMapNotReadyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(new ErrorResponse(SECTOR_MAP_NOT_READY, e.getMessage())));
    }

    /**
     * 저장된 대응표가 도메인 불변식을 어길 때. <b>400이 아니라 500이다.</b>
     *
     * <p>{@code SectorMapper.toDomain}이 만드는 {@code SectorCode}·{@code Sector}·{@code
     * RepresentativeEtf}는 값이 규칙에 안 맞으면 {@link IllegalArgumentException}을 던진다. 그런데 {@code GET
     * /sectors}는 클라이언트에게서 받는 입력이 하나도 없다. 여기서 그 예외가 나왔다면 인자의 출처는 DB뿐이다.
     *
     * <p>그대로 흘려보내면 {@code GlobalExceptionHandler}가 400 {@code INVALID_INPUT}으로 옮긴다. 그러면 두 가지가 잘못된다.
     * 고칠 것이 없는 클라이언트에게 "네 입력이 틀렸다"고 말하게 되고, 서버 쪽 자료 결함이 4xx로 분류되어 5xx 경보에 잡히지 않는다.
     *
     * <p>이런 행이 들어올 수 있는 까닭은 {@code sector.code}·{@code sector.name}·{@code
     * representative_etf.manager}에 형식 CHECK가 없어서다. 파이프라인이 막고 있지만 {@code psql}로 직접 넣으면 통과한다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnusableMap(IllegalArgumentException e) {
        log.error("저장된 대응표가 도메인 불변식을 어긴다. 파이프라인이 발행한 자료를 확인해야 한다", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                        ApiResponse.fail(
                                new ErrorResponse(SECTOR_MAP_UNUSABLE, MAP_UNUSABLE_MESSAGE)));
    }
}
