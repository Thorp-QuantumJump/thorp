package com.ssafy.thorp.marketflow.presentation;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.mail.username=market-flow-test",
            "spring.mail.password=market-flow-test"
        })
@AutoConfigureMockMvc
class MarketFlowControllerIT extends PostgresTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void removeExampleReadModel() {
        jdbc.update(
                "DELETE FROM market_flow_field_summary WHERE registry_version = ? AND dataset_version = ?",
                "macro-registry-example-v1",
                "macro-dataset-example-2026-09-16");
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

    @Test
    void listEndpointRejectsSectorAndOtherQueryParameters() throws Exception {
        mockMvc.perform(get("/market-flow/fields").param("sector", "semiconductor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false));
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
                                .value("2026-09"))
                .andExpect(jsonPath("$.result.indicators[1].path[2].month").value("2026-08"));
    }

    @Test
    void unknownFieldIsBadRequest() throws Exception {
        mockMvc.perform(get("/market-flow/fields/unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.httpStatus").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.isSuccess").value(false));
    }
}
