package com.ssafy.thorp.regime.presentation.controller;

import com.ssafy.thorp.common.web.response.HttpApiResponse;
import com.ssafy.thorp.regime.application.service.RegimeSearchService;
import com.ssafy.thorp.regime.domain.vo.SearchMode;
import com.ssafy.thorp.regime.presentation.response.BaselineMonthsResponse;
import com.ssafy.thorp.regime.presentation.response.RegimeCandidatesResponse;
import com.ssafy.thorp.regime.presentation.response.RegimeCaseDetailResponse;
import com.ssafy.thorp.regime.presentation.response.RegimeSearchResponse;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 경제환경 검색 조회 API (계약 {@code contracts/regime-search-api.md}).
 *
 * <p><b>동기 조회다.</b> 접수·상태 조회가 없고 Job 큐를 쓰지 않는다 — 요청은 사전 계산된 결과를 읽을 뿐이고 요청 처리 중에 척도도 거리도 계산하지
 * 않는다(spec FR-030, SC-002a).
 *
 * <p><b>인증이 필요 없다.</b> 비로그인으로 전부 볼 수 있다(spec FR-032) — 검색 결과는 여러 사용자가 공유하는 자원이고, 기록을 붙일 주인이 없어도
 * 재사용이 성립한다.
 *
 * <p><b>거리 함수·척도·지표 조합을 고르는 파라미터를 두지 않는다</b>(US1 시나리오 5). 넣으면 사용자가 모델을 고르는 화면이 생긴다.
 */
@RestController
@RequestMapping("/regime-searches")
public class RegimeSearchController {

    /** 처음 보이는 개수. '더 보기'면 10이다(계약 §1). */
    private static final int DEFAULT_LIMIT = 3;

    private final RegimeSearchService searchService;

    public RegimeSearchController(RegimeSearchService searchService) {
        this.searchService = searchService;
    }

    /** §1 — 검색 하나를 읽는다. 계산 불가도 <b>200</b>으로 나간다. */
    @GetMapping
    public HttpApiResponse<RegimeSearchResponse> search(
            @RequestParam String mode,
            @RequestParam(required = false) String field,
            @RequestParam(required = false) String baselineMonth,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return HttpApiResponse.ok(
                RegimeSearchResponse.from(
                        searchService.search(
                                SearchMode.from(mode), field, month(baselineMonth), limit)));
    }

    /**
     * §2 — 고정된 항목을 지목해 읽는다.
     *
     * <p>006 기록이 고정한 검색을 나중에 다시 열 때 쓴다. §1은 늘 최신 {@code revision}을 주므로, 자료가 개정된 뒤에는 그때 본 결과를 열 수
     * 없다.
     */
    @GetMapping("/{searchId}")
    public HttpApiResponse<RegimeSearchResponse> searchById(
            @PathVariable String searchId,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return HttpApiResponse.ok(
                RegimeSearchResponse.from(searchService.searchById(searchId, limit)));
    }

    /**
     * §3 — 사례 상세.
     *
     * <p><b>어느 검색의 사례인지</b>가 있어야 뜻이 선다 — 기준월이 다르면 같은 {@code endMonth}라도 z와 거리가 다르다. 고정된 항목의 사례는
     * {@code searchId}로 연다.
     */
    @GetMapping("/cases/{endMonth}")
    public HttpApiResponse<RegimeCaseDetailResponse> caseDetail(
            @PathVariable String endMonth,
            @RequestParam(required = false) String searchId,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String field,
            @RequestParam(required = false) String baselineMonth) {
        YearMonth month = requireMonth(endMonth);
        if (searchId != null) {
            return HttpApiResponse.ok(
                    RegimeCaseDetailResponse.from(searchService.caseDetailById(searchId, month)));
        }
        return HttpApiResponse.ok(
                RegimeCaseDetailResponse.from(
                        searchService.caseDetail(
                                SearchMode.from(mode), field, month(baselineMonth), month)));
    }

    /**
     * §4 — 전체 유효 후보의 순위·거리 목록.
     *
     * <p>011이 ETF 가용성 필터를 얹을 때 쓴다. <b>010은 이 목록에 필터도 ±6개월 건너뛰기도 적용하지 않는다</b>(spec FR-012·FR-030).
     */
    @GetMapping("/candidates")
    public HttpApiResponse<RegimeCandidatesResponse> candidates(
            @RequestParam(required = false) String searchId,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String field,
            @RequestParam(required = false) String baselineMonth) {
        if (searchId != null) {
            return HttpApiResponse.ok(
                    RegimeCandidatesResponse.from(searchService.candidatesById(searchId)));
        }
        return HttpApiResponse.ok(
                RegimeCandidatesResponse.from(
                        searchService.candidates(
                                SearchMode.from(mode), field, month(baselineMonth))));
    }

    /**
     * §5 — 화면이 고를 수 있는 기준월.
     *
     * <p><b>분야 이름은 등록표 포트에서, 기준월과 범위는 배치가 남긴 표에서</b> 온다. 기준월을 008에 직접 묻지 않는 이유는 계약 §0.2다 — 008이 올린
     * 최신 T_g는 배치가 아직 계산하지 않았을 수 있고, 그 달로 검색하면 404가 난다.
     */
    @GetMapping("/baseline-months")
    public HttpApiResponse<BaselineMonthsResponse> baselineMonths() {
        return HttpApiResponse.ok(BaselineMonthsResponse.from(searchService.baselineMonths()));
    }

    private static YearMonth month(String value) {
        return value == null || value.isBlank() ? null : requireMonth(value);
    }

    private static YearMonth requireMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("월은 YYYY-MM 형식이어야 합니다: " + value, e);
        }
    }
}
