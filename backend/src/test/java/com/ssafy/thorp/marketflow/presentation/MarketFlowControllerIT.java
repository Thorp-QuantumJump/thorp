package com.ssafy.thorp.marketflow.presentation;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.macro.support.MacroRegistrySeed;
import com.ssafy.thorp.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class MarketFlowControllerIT extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    /**
     * 008 표를 채우고 시작한다 (T071).
     *
     * <p>예시 자료 대역을 걷어낸 뒤로 이 시험은 <b>008 표에서 읽어 화면까지 가는 실제 경로</b>를 돈다 — 등록표·자료 버전·관측·기준월이 없으면 조회가 500
     * 이다. 2026-09-21 운영이 멈춘 자리가 008과 009 사이였고, 그 사이를 실제로 이어 본 시험이 없었다.
     */
    @BeforeEach
    void seedMacroSnapshot() {
        MacroRegistrySeed.seedRegistry(jdbc);
        MacroRegistrySeed.seedPublishedDataset(jdbc);
        // 노동 분야의 둘째 지표(실업률)만 기준월(2026-07)보다 한 달 앞서 발표된 상태로 둔다.
        MacroRegistrySeed.seedLaterRelease(jdbc, "unemployment_rate", "2026-08-01");
    }

    @AfterEach
    void removeSeededData() {
        // 분야 요약은 (등록표 버전, 자료 버전) 으로 저장된다. 지우지 않으면 다음 시험이 앞
        // 시험의 읽기 모델을 그대로 쓴다.
        jdbc.update("DELETE FROM market_flow_field_summary");
        // 008 표도 되돌린다. 컨테이너를 다른 시험과 함께 쓰므로, 남겨 두면 "등록표가 아직
        // 없다"를 보는 시험이 등록표를 발견한다.
        MacroRegistrySeed.clear(jdbc);
    }

    @Test
    void anonymousUserGetsAllSevenFieldsWithExplicitNullableKeys() throws Exception {
        mockMvc.perform(get("/market-flow/fields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpStatus").value("OK"))
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.fields.length()").value(7))
                .andExpect(jsonPath("$.result.fields[0].narrative").exists())
                .andExpect(jsonPath("$.result.historicalComparisonNotice").isNotEmpty());
    }

    /**
     * 이 엔드포인트는 허용 목록이 비어 있다. 이름을 가리지 않고 거부하는 것이 의도다.
     *
     * <p>섹터가 경제환경 표시를 바꿀 수 없어야 하는데(FR-016, SC-004), 조용히 무시하면 호출자가 걸러진 줄 착각한다. 같은 화면이 함께 쓰는 012 뉴스
     * API는 {@code sectorId}를 실제로 받아 필터하므로 그 호출을 복사해 오기 쉽다. 그렇다고 {@code sectorId}라는 이름만 막으면 {@code
     * sector}·{@code sectorCode} 같은 다른 철자가 그대로 통과한다. 그래서 금지 목록이 아니라 빈 허용 목록으로 뒀다.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "sectorId=semiconductor",
                "sector=semiconductor",
                "sectorCode=SEMI",
                "utm_source=test",
                "random=1",
                "_=1699",
                "sector=semiconductor&utm_source=test",
                "sectorId=semiconductor&random=1&_=1699"
            })
    void listEndpointRejectsEveryQueryParameter(String queryString) throws Exception {
        mockMvc.perform(get("/market-flow/fields?" + queryString))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.httpStatus").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.result").isEmpty());
    }

    @Test
    void detailContainsActualValuesSourcesAgencyAndSeparatedLaterRelease() throws Exception {
        mockMvc.perform(get("/market-flow/fields/labor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.fieldId").value("labor"))
                .andExpect(jsonPath("$.result.indicators.length()").value(2))
                .andExpect(jsonPath("$.result.indicators[0].value").isNumber())
                .andExpect(jsonPath("$.result.indicators[0].source").isNotEmpty())
                .andExpect(jsonPath("$.result.indicators[0].agency").isNotEmpty())
                .andExpect(
                        jsonPath(
                                "$.result.indicators[*].agency",
                                everyItem(not(emptyOrNullString()))))
                .andExpect(
                        jsonPath("$.result.indicators[1].afterReferenceMonthRelease.referenceMonth")
                                .value("2026-08"))
                .andExpect(jsonPath("$.result.indicators[1].path[2].month").value("2026-07"));
    }

    @Test
    void unknownFieldIsBadRequest() throws Exception {
        mockMvc.perform(get("/market-flow/fields/unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.httpStatus").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.isSuccess").value(false));
    }
}
