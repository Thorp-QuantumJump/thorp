package com.ssafy.thorp.marketflow.presentation.controller;

import com.ssafy.thorp.common.web.response.HttpApiResponse;
import com.ssafy.thorp.marketflow.application.service.MarketFlowQueryService;
import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import com.ssafy.thorp.marketflow.presentation.response.MarketFlowFieldDetailResponse;
import com.ssafy.thorp.marketflow.presentation.response.MarketFlowFieldsResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 로그인과 전략 선택 없이 시장 흐름의 일곱 분야 요약·상세를 조회하는 읽기 전용 API. */
@RestController
@RequestMapping("/market-flow")
public class MarketFlowController {

    private final MarketFlowQueryService queryService;

    public MarketFlowController(MarketFlowQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/fields")
    public HttpApiResponse<MarketFlowFieldsResponse> getFields(
            @RequestParam Map<String, String> queryParameters) {
        if (!queryParameters.isEmpty()) {
            throw new IllegalArgumentException("시장 흐름 분야 조회는 요청 파라미터를 받지 않습니다.");
        }
        return HttpApiResponse.ok(MarketFlowFieldsResponse.from(queryService.getFields()));
    }

    @GetMapping("/fields/{fieldId}")
    public HttpApiResponse<MarketFlowFieldDetailResponse> getField(@PathVariable String fieldId) {
        return HttpApiResponse.ok(
                MarketFlowFieldDetailResponse.from(queryService.getField(FieldId.from(fieldId))));
    }
}
