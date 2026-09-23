package com.ssafy.thorp.sector.presentation.controller;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.sector.application.command.EvaluationWindowQuery;
import com.ssafy.thorp.sector.application.service.EvaluationWindowQueryService;
import com.ssafy.thorp.sector.application.service.SectorService;
import com.ssafy.thorp.sector.presentation.response.EvaluationWindowResponse;
import com.ssafy.thorp.sector.presentation.response.InstrumentProfileResponse;
import com.ssafy.thorp.sector.presentation.response.SectorListResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 섹터 목록·대표 상품 정보 조회 API (contracts/sector-api.md, User Story 1·3).
 *
 * <p>읽기만 있다. 대응표를 만들거나 고치는 엔드포인트는 두지 않는다 — 원본은 {@code data/} 파이프라인이 소유한다(research.md 결정 7).
 *
 * <p>가격 시계열은 이 계약으로 내보내지 않는다.
 */
@RestController
@RequestMapping("/sectors")
public class SectorController {

    private final SectorService sectorService;
    private final EvaluationWindowQueryService evaluationWindowQueryService;

    public SectorController(
            SectorService sectorService,
            EvaluationWindowQueryService evaluationWindowQueryService) {
        this.sectorService = sectorService;
        this.evaluationWindowQueryService = evaluationWindowQueryService;
    }

    /**
     * 섹터 목록. 응답에 {@code mapVersion}(FR-003)과 {@code isSample}(FR-002)이 함께 실린다.
     *
     * <p>대응표가 발행되지 않았으면 빈 배열이 아니라 409 {@code SECTOR_MAP_NOT_READY}다({@link
     * SectorExceptionHandler}).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<SectorListResponse>> getSectors() {
        return ResponseEntity.ok(
                ApiResponse.ok(SectorListResponse.from(sectorService.getSectors())));
    }

    /**
     * 대표 상품 정보. User Story 3이 요구하는 펼쳐 보는 층이다.
     *
     * <p>없는 섹터 코드는 404 {@code SECTOR_NOT_FOUND}, 자료가 아직 만들어지지 않았으면 409 {@code
     * INSTRUMENT_PROFILE_NOT_READY}다({@link SectorExceptionHandler}). <b>빈 값을 만들어 내지 않는다.</b>
     */
    @GetMapping("/{code}/instrument-profile")
    public ResponseEntity<ApiResponse<InstrumentProfileResponse>> getInstrumentProfile(
            @PathVariable String code) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        InstrumentProfileResponse.from(sectorService.getInstrumentProfile(code))));
    }

    /**
     * 공통 평가 가능 기간. 003의 "이 조합은 언제부터 비교 가능합니다" 안내가 쓴다(FR-007).
     *
     * <p><b>{@code strategy}와 {@code maxWarmupTradingDays}는 둘 다 선택이지만 적어도 하나는 있어야 한다.</b> 전략을 주면 그
     * 전략의 기본 설정 워밍업으로 판정하고, 상한을 주면 화면이 다른 워밍업으로 스스로 다시 판정할 거래일 목록을 함께 내려 준다. 상한을 주지 않았을 때 등록표의
     * 최대값으로 채우지 않는 이유는 001이 003의 파라미터 조합을 알지 않게 하려는 것이다 — 화면이 자기가 필요한 만큼을 말한다.
     *
     * <p>없는 섹터 코드는 404 {@code SECTOR_NOT_FOUND}, 대응표 미발행은 409 {@code SECTOR_MAP_NOT_READY}, 자료
     * 버전·거래일 달력이 아직 없으면 409 {@code EVALUATION_WINDOW_NOT_READY}다({@link SectorExceptionHandler}).
     * <b>평가할 수 없는 조합은 오류가 아니라 {@code evaluable: false}인 200이다.</b>
     */
    @GetMapping("/evaluation-window")
    public ResponseEntity<ApiResponse<EvaluationWindowResponse>> getEvaluationWindow(
            @RequestParam(name = "sectors", required = false) List<String> sectors,
            @RequestParam(name = "strategy", required = false) String strategy,
            @RequestParam(name = "maxWarmupTradingDays", required = false)
                    Integer maxWarmupTradingDays) {
        EvaluationWindowQuery query = toQuery(sectors, strategy, maxWarmupTradingDays);
        return ResponseEntity.ok(
                ApiResponse.ok(
                        EvaluationWindowResponse.from(
                                evaluationWindowQueryService.getEvaluationWindow(query))));
    }

    /**
     * 질의 문자열을 유스케이스 입력으로 옮긴다. 값이 규칙에 맞지 않으면 400이다.
     *
     * <p><b>{@link IllegalArgumentException}을 그대로 흘려보내지 않는다.</b> {@link SectorExceptionHandler}가 그
     * 예외를 <b>저장된 자료의 결함</b>으로 보아 500으로 옮기기 때문이다 — 클라이언트가 잘못 보낸 질의 때문에 5xx 경보가 울린다.
     */
    private static EvaluationWindowQuery toQuery(
            List<String> sectors, String strategy, Integer maxWarmupTradingDays) {
        try {
            return new EvaluationWindowQuery(sectors, strategy, maxWarmupTradingDays);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }
}
