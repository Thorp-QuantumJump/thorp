package com.ssafy.thorp.marketflow.presentation;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.marketflow.application.result.MarketFlowSnapshot;
import com.ssafy.thorp.marketflow.application.service.MarketFlowQueryService;
import com.ssafy.thorp.marketflow.domain.model.MarketFlowField;
import com.ssafy.thorp.marketflow.domain.model.NarrativeFormatter;
import com.ssafy.thorp.marketflow.domain.vo.FieldId;
import com.ssafy.thorp.marketflow.domain.vo.FieldStatus;
import com.ssafy.thorp.marketflow.presentation.controller.MarketFlowController;
import com.ssafy.thorp.marketflow.presentation.controller.MarketFlowExceptionHandler;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MarketFlowController.class)
@Import({SecurityConfig.class, MarketFlowExceptionHandler.class})
class MarketFlowControllerContractTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MarketFlowQueryService queryService;

    @Test
    void emptyAndCollectionFailedRemainHttp200WithExplicitNullNarrative() throws Exception {
        List<MarketFlowField> fields =
                Arrays.stream(FieldId.values())
                        .map(
                                id -> {
                                    FieldStatus status =
                                            id == FieldId.PRODUCTION
                                                    ? FieldStatus.EMPTY
                                                    : id == FieldId.CONSUMPTION_INVESTMENT
                                                            ? FieldStatus.COLLECTION_FAILED
                                                            : FieldStatus.READY;
                                    return field(id, status);
                                })
                        .toList();
        when(queryService.getFields())
                .thenReturn(new MarketFlowSnapshot("registry-v1", "dataset-v1", fields));

        mockMvc.perform(get("/market-flow/fields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.fields.length()").value(7))
                .andExpect(jsonPath("$.result.fields[0].status").value("EMPTY"))
                .andExpect(jsonPath("$.result.fields[0].narrative").value(nullValue()))
                .andExpect(jsonPath("$.result.fields[1].status").value("COLLECTION_FAILED"))
                .andExpect(jsonPath("$.result.fields[1].narrative").value(nullValue()));
    }

    private static MarketFlowField field(FieldId id, FieldStatus status) {
        boolean unavailable =
                status == FieldStatus.EMPTY || status == FieldStatus.COLLECTION_FAILED;
        return new MarketFlowField(
                id,
                id.value(),
                id.ordinal() + 1,
                status,
                unavailable ? null : "변화 요약",
                NarrativeFormatter.RULE_VERSION,
                List.of(),
                unavailable ? null : YearMonth.of(2026, 8),
                List.of(),
                unavailable ? null : Instant.parse("2026-09-16T02:00:00Z"),
                false,
                List.of());
    }
}
