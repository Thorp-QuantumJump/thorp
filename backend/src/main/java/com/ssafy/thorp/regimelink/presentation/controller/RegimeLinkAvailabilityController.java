package com.ssafy.thorp.regimelink.presentation.controller;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.regimelink.application.command.AvailabilityQuery;
import com.ssafy.thorp.regimelink.application.service.AvailabilityService;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.EntryPath;
import com.ssafy.thorp.regimelink.presentation.response.AvailabilityResponse;
import com.ssafy.thorp.regimelink.presentation.response.CaseWindowResponse;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사례 연결 가용성 조회 (contracts §1·§1a).
 *
 * <p><b>익명으로 볼 수 있다</b>(FR-025). 여기 있는 것은 읽기뿐이고 계산도 기록도 만들지 않는다 — 로그인을 요구하는 것은 목록을 확정하는 POST
 * 부터다(research R7).
 *
 * <p><b>011은 {@code ErrorCode}에 코드를 더하지 않는다</b>(T029). 형식 오류는 {@code INVALID_INPUT}(400), 없는 사례·검색은
 * {@code RESOURCE_NOT_FOUND}(404), 공개 토큰의 만료는 005가 쓰던 {@code RESULT_EXPIRED}(410)를 그대로 쓴다. 같은 거절을
 * 경로마다 다른 코드로 내면 화면이 두 벌로 분기한다.
 *
 * <p><b>분석 기간 밖은 오류가 아니다.</b> 200 응답의 {@code availability.state=OUT_OF_PERIOD}로 나가고, 사용자가 확인하면 전체
 * 기간으로 다시 계산한다(계약 §2).
 */
@RestController
@RequestMapping("/regime-links")
public class RegimeLinkAvailabilityController {

    private final AvailabilityService availabilityService;

    public RegimeLinkAvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    /**
     * §1 — 표시 후보에 가용성 판정을 붙인다.
     *
     * <p>010 검색이 계산 불가여도, 필터 뒤에 남는 사례가 0건이어도 <b>200</b>이다(FR-019).
     */
    @GetMapping("/availability")
    public ApiResponse<AvailabilityResponse> availability(
            @RequestParam String base,
            @RequestParam(required = false) String compare,
            @RequestParam(required = false) String snapshotKey,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String field,
            @RequestParam(required = false) String baselineMonth,
            @RequestParam String entryPath) {
        AvailabilityQuery query =
                new AvailabilityQuery(
                        base,
                        compare,
                        snapshotKey,
                        mode,
                        field,
                        month(baselineMonth),
                        entryPath(entryPath));
        return ApiResponse.ok(AvailabilityResponse.from(availabilityService.evaluate(query)));
    }

    /**
     * §1a — 실행 토큰 없이 사례의 사후 구간을 읽는다.
     *
     * <p>003이 분석 기간을 고르기 <b>전에</b> 부르므로 비교 묶음을 요구하지 않는다. 공개 조회다.
     */
    @GetMapping("/case-window")
    public ApiResponse<CaseWindowResponse> caseWindow(@RequestParam String caseId) {
        return ApiResponse.ok(
                CaseWindowResponse.from(availabilityService.caseWindow(caseRef(caseId))));
    }

    private static CaseRef caseRef(String caseId) {
        try {
            return CaseRef.parse(caseId);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, malformed.getMessage());
        }
    }

    private static EntryPath entryPath(String value) {
        try {
            return EntryPath.from(value);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, malformed.getMessage());
        }
    }

    private static YearMonth month(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException malformed) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, "기준월은 YYYY-MM 형식이어야 합니다: " + value);
        }
    }
}
