package com.ssafy.thorp.sector.presentation.controller;

import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.common.web.response.ErrorResponse;
import com.ssafy.thorp.sector.application.exception.EvaluationWindowNotReadyException;
import com.ssafy.thorp.sector.application.exception.InstrumentProfileNotReadyException;
import com.ssafy.thorp.sector.application.exception.PriceSeriesNotReadyException;
import com.ssafy.thorp.sector.application.exception.SectorMapNotReadyException;
import com.ssafy.thorp.sector.application.exception.SectorNotFoundException;
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
 *   <caption>상황별 응답</caption>
 *   <tr><th>상황</th><th>상태</th><th>코드</th></tr>
 *   <tr><td>대응표가 발행되지 않음</td><td>409</td><td>{@code SECTOR_MAP_NOT_READY}</td></tr>
 *   <tr><td>없는 섹터 코드</td><td>404</td><td>{@code SECTOR_NOT_FOUND}</td></tr>
 *   <tr><td>대표 상품 정보가 아직 없음</td><td>409</td><td>{@code INSTRUMENT_PROFILE_NOT_READY}</td></tr>
 *   <tr><td>평가 기간 산출 자료가 아직 없음</td><td>409</td><td>{@code EVALUATION_WINDOW_NOT_READY}</td></tr>
 *   <tr><td>가격 자료가 아직 없음</td><td>409</td><td>{@code PRICE_SERIES_NOT_READY}</td></tr>
 * </table>
 *
 * <p>이 다섯만 처리한다. 나머지(예상 못한 예외 → 500)는 {@code GlobalExceptionHandler}로 그대로 흘려보낸다 — catch-all을 여기에 두면
 * 전역 처리기의 로깅·메시지 정책이 이 도메인에서만 달라진다.
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
    static final String SECTOR_NOT_FOUND = "SECTOR_NOT_FOUND";
    static final String INSTRUMENT_PROFILE_NOT_READY = "INSTRUMENT_PROFILE_NOT_READY";
    static final String EVALUATION_WINDOW_NOT_READY = "EVALUATION_WINDOW_NOT_READY";
    static final String PRICE_SERIES_NOT_READY = "PRICE_SERIES_NOT_READY";

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
     * 열린 대응표에 없는 섹터 코드. 재시도해도 달라지지 않으므로 404다.
     *
     * <p>형식이 틀린 코드도 여기로 온다 — 서비스가 경로 변수로 {@code SectorCode}를 만들지 않고 목록에서 찾기만 하므로, 형식 위반은 "찾지 못함"과
     * 같은 길을 탄다. 계약이 이 경로에 정의한 오류도 404 하나뿐이다.
     */
    @ExceptionHandler(SectorNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleSectorNotFound(SectorNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(new ErrorResponse(SECTOR_NOT_FOUND, e.getMessage())));
    }

    /**
     * 대표 상품 정보 미적재. 섹터는 있으므로 404가 아니고, 파이프라인이 적재하면 달라지므로 409다.
     *
     * <p><b>빈 값을 만들어 내지 않는다.</b> 유동성 0원이나 빈 구성종목 목록으로 내보내면 "거래가 없었다"·"중복이 없다"로 읽힌다(원칙 VI).
     */
    @ExceptionHandler(InstrumentProfileNotReadyException.class)
    public ResponseEntity<ApiResponse<Void>> handleProfileNotReady(
            InstrumentProfileNotReadyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        ApiResponse.fail(
                                new ErrorResponse(INSTRUMENT_PROFILE_NOT_READY, e.getMessage())));
    }

    /**
     * 가격 미적재. 섹터도 대표 ETF도 있으므로 404가 아니고, 파이프라인이 적재하면 달라지므로 409다.
     *
     * <p><b>빈 배열을 만들어 내지 않는다.</b> 점 없는 그래프를 내보내면 화면은 "값이 없다"가 아니라 "움직임이 없었다"로 그린다(원칙 VI).
     */
    @ExceptionHandler(PriceSeriesNotReadyException.class)
    public ResponseEntity<ApiResponse<Void>> handlePriceSeriesNotReady(
            PriceSeriesNotReadyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(new ErrorResponse(PRICE_SERIES_NOT_READY, e.getMessage())));
    }

    /**
     * 평가 기간 산출 자료 미적재. 섹터도 대응표도 있으므로 404가 아니고, 파이프라인이 적재하면 달라지므로 409다.
     *
     * <p><b>평가 불가는 여기로 오지 않는다.</b> 워밍업을 채울 거래일이 모자라거나 구간이 뒤집힌 것은 오류가 아니라 {@code evaluable = false}인
     * 200이며, 화면은 "이 조합은 분석할 수 없습니다" 한 문장을 보인다(원칙 VI). 둘을 같은 실패로 뭉개면 화면이 "잠시 후 다시"와 "이 조합은 안 됩니다"를
     * 가릴 수 없다.
     */
    @ExceptionHandler(EvaluationWindowNotReadyException.class)
    public ResponseEntity<ApiResponse<Void>> handleEvaluationWindowNotReady(
            EvaluationWindowNotReadyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        ApiResponse.fail(
                                new ErrorResponse(EVALUATION_WINDOW_NOT_READY, e.getMessage())));
    }

    /**
     * 저장된 대응표가 도메인 불변식을 어길 때. <b>400이 아니라 500이다.</b>
     *
     * <p>{@code SectorMapper.toDomain}이 만드는 {@code SectorCode}·{@code Sector}·{@code
     * RepresentativeEtf}와 {@code InstrumentProfileMapper}가 만드는 {@code InstrumentProfile}은 값이 규칙에 안
     * 맞으면 {@link IllegalArgumentException}을 던진다. 그런데 이 도메인의 두 엔드포인트가 클라이언트에게서 받는 입력은 {@code
     * /sectors/{code}}의 코드 하나뿐이고, 그 값은 도메인 객체를 만드는 데 쓰이지 않는다 — 목록에서 같은 문자열을 찾을 뿐이고 못 찾으면 {@link
     * SectorNotFoundException}이다. 그러니 여기서 그 예외가 나왔다면 인자의 출처는 DB뿐이다.
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
