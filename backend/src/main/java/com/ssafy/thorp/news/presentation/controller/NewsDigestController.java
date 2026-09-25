package com.ssafy.thorp.news.presentation.controller;

import com.ssafy.thorp.common.web.response.HttpApiResponse;
import com.ssafy.thorp.news.application.service.NewsArticleExportService;
import com.ssafy.thorp.news.application.service.NewsDigestQueryService;
import com.ssafy.thorp.news.presentation.response.NewsArticleExportResponse;
import com.ssafy.thorp.news.presentation.response.NewsDigestResponse;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시장 흐름 뉴스 묶음 조회(contracts/openapi.yaml).
 *
 * <p>외부 경로는 {@code /api/market-news/digests}이고 {@code /api}는 프록시가 떼므로 여기 매핑은 {@code
 * /market-news/digests}다.
 *
 * <p>엔드포인트는 하나다. 기간을 주지 않으면 현재 뉴스, {@code from}·{@code to}를 함께 주면 그 구간의 당시 뉴스다. 둘을 갈라 두 경로로 만들지 않는
 * 이유는 화면이 같은 자리에서 시점만 바꿔 보기 때문이다 — 경로가 갈리면 상태·정렬 규칙이 두 벌로 갈라진다.
 *
 * <p><b>뉴스원을 고르는 파라미터를 두지 않는다.</b> 어느 매체·API에서 왔는지는 사용자가 정할 일이 아니다(spec FR-008). 노출 모드도 서버 설정이며 질의
 * 문자열로 바꿀 수 없다(spec FR-016).
 *
 * <p>빈 결과·수집 지연·허가 대기는 전부 HTTP 200이다. 외부 뉴스원 장애가 시장 흐름 화면의 오류가 되지 않는다(spec FR-024).
 */
@RestController
@RequestMapping("/market-news")
public class NewsDigestController {

    private final NewsDigestQueryService queryService;
    private final NewsArticleExportService exportService;
    private final com.ssafy.thorp.news.application.service.NewsSectorListService sectorList;

    public NewsDigestController(
            NewsDigestQueryService queryService,
            NewsArticleExportService exportService,
            com.ssafy.thorp.news.application.service.NewsSectorListService sectorList) {
        this.queryService = queryService;
        this.exportService = exportService;
        this.sectorList = sectorList;
    }

    /**
     * 뉴스를 좁혀 볼 수 있는 섹터 목록(spec FR-005, Session 2026-09-25).
     *
     * <p>투자 대상 섹터(001에 열린 것)와 뉴스 전용 섹터를 화면 순서로 준다. 화면이 섹터 이름을 상수로 들고 있지 않게 하려는 것이다.
     */
    @GetMapping("/sectors")
    public ResponseEntity<
                    HttpApiResponse<
                            com.ssafy.thorp.news.presentation.response.NewsSectorListResponse>>
            getSectors() {
        return ResponseEntity.ok(
                HttpApiResponse.ok(
                        com.ssafy.thorp.news.presentation.response.NewsSectorListResponse.from(
                                sectorList.list())));
    }

    /**
     * 현재 또는 지정 구간의 뉴스 묶음.
     *
     * @param sectorId 좁힐 섹터. spec 001이 소유하는 식별자이며 등록되지 않은 값은 400이다.
     * @param from 과거 구간 시작. {@code to}와 함께 줘야 한다.
     * @param to 과거 구간 끝. 구간 상한은 서버 설정이 정한다.
     */
    @GetMapping("/digests")
    public ResponseEntity<HttpApiResponse<NewsDigestResponse>> getDigests(
            @RequestParam(required = false) String sectorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to) {

        return ResponseEntity.ok(
                HttpApiResponse.ok(
                        NewsDigestResponse.from(queryService.query(sectorId, from, to))));
    }

    /**
     * 허가 범위 안의 기사 메타데이터를 내보낸다(spec FR-017).
     *
     * <p><b>여기는 노출 모드를 보지 않는다.</b> 회신이 없거나 불허인 매체의 항목은 어떤 환경에서도 나가지 않는다. 화면은 닫으면 사라지지만 내보낸 자료는 남기
     * 때문이다.
     *
     * <p>기간이 필수다. 범위를 정하지 않으면 저장된 전부를 뜨게 된다.
     *
     * @param sectorId 좁힐 섹터. 등록되지 않은 값은 400이다.
     * @param from 구간 시작.
     * @param to 구간 끝.
     * @param cursor 이전 페이지의 마지막 자리. 첫 페이지면 생략한다.
     * @param limit 한 페이지의 최대 항목 수. 생략하면 기본값을 쓴다.
     */
    @GetMapping("/articles/export")
    public ResponseEntity<HttpApiResponse<NewsArticleExportResponse>> exportArticles(
            @RequestParam(required = false) String sectorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return ResponseEntity.ok(
                HttpApiResponse.ok(
                        "허가된 뉴스 메타데이터를 조회했습니다.",
                        NewsArticleExportResponse.from(
                                exportService.export(sectorId, from, to, cursor, limit))));
    }
}
