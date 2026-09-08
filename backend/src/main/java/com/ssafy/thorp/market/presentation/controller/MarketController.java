package com.ssafy.thorp.market.presentation.controller;

import com.ssafy.thorp.common.web.response.HttpApiResponse;
import com.ssafy.thorp.market.application.service.MarketService;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import com.ssafy.thorp.market.presentation.response.MarketResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 외부 /api/markets 경로의 /api 접두사는 프록시에서 제거한다. */
@RestController
@RequestMapping("/markets")
public class MarketController {
    private final MarketService service;

    public MarketController(MarketService service) {
        this.service = service;
    }

    @GetMapping("/kospi")
    public HttpApiResponse<MarketResponse> kospi() {
        return get(MarketCode.KOSPI);
    }

    @GetMapping("/kosdaq")
    public HttpApiResponse<MarketResponse> kosdaq() {
        return get(MarketCode.KOSDAQ);
    }

    @GetMapping("/usd-krw")
    public HttpApiResponse<MarketResponse> usd_krw() {
        return get(MarketCode.USD_KRW);
    }

    @GetMapping("/vkospi200")
    public HttpApiResponse<MarketResponse> vkospi200() {
        return get(MarketCode.VKOSPI200);
    }

    @GetMapping("/ktb-3y")
    public HttpApiResponse<MarketResponse> ktb_3y() {
        return get(MarketCode.KTB_3Y);
    }

    @GetMapping("/coincident-cycle")
    public HttpApiResponse<MarketResponse> coincident_cycle() {
        return get(MarketCode.COINCIDENT_CYCLE);
    }

    @GetMapping("/leading-cycle")
    public HttpApiResponse<MarketResponse> leading_cycle() {
        return get(MarketCode.LEADING_CYCLE);
    }

    @GetMapping("/esi-cycle")
    public HttpApiResponse<MarketResponse> esi_cycle() {
        return get(MarketCode.ESI_CYCLE);
    }

    @GetMapping("/core-cpi-yoy")
    public HttpApiResponse<MarketResponse> core_cpi_yoy() {
        return get(MarketCode.CORE_CPI_YOY);
    }

    @GetMapping("/exports-usd")
    public HttpApiResponse<MarketResponse> exports_usd() {
        return get(MarketCode.EXPORTS_USD);
    }

    @GetMapping("/m2")
    public HttpApiResponse<MarketResponse> m2() {
        return get(MarketCode.M2);
    }

    private HttpApiResponse<MarketResponse> get(MarketCode code) {
        return HttpApiResponse.ok(MarketResponse.from(service.get(code)));
    }
}
