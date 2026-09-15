package com.ssafy.thorp.news.presentation;

import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.MAPPER;
import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.example;
import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.mockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import com.ssafy.thorp.news.domain.vo.NewsDigestState;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 과거 구간에 자료가 없을 때의 응답(spec FR-006, T030).
 *
 * <p>빈 구간은 정상이다. 요청 구간 상한 93일은 "그 범위를 요청할 수 있다"는 뜻이지 "그 범위에 자료가 있다"는 뜻이 아니다(plan "조회와 상태"). 요청한 구간을
 * 응답에 그대로 실어 화면이 무엇을 물었는지 보일 수 있게 한다.
 */
class NewsHistoricalDigestContractTest {

    private static NewsDigestListResult historicalEmpty(NewsDigestQuery query) {
        return new NewsDigestListResult(
                query.context(),
                NewsDigestState.EMPTY,
                query.sectorId(),
                query.from(),
                query.to(),
                Instant.parse("2026-09-10T06:15:00Z"),
                false,
                "해당 기간에 표시할 수 있는 당시 뉴스가 없습니다.",
                List.of());
    }

    @Test
    @DisplayName("빈 과거 구간 응답이 계약 예시와 같다")
    void 계약_예시_일치() throws Exception {
        String body =
                mockMvc(NewsHistoricalDigestContractTest::historicalEmpty)
                        .perform(
                                get("/market-news/digests")
                                        .param("from", "2026-07-01T00:00:00Z")
                                        .param("to", "2026-09-30T23:59:59Z"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(MAPPER.readTree(body)).isEqualTo(example("historical-empty.json"));
    }

    @Test
    @DisplayName("요청한 구간을 응답에 그대로 실어 보낸다")
    void 요청_구간_반영() throws Exception {
        mockMvc(NewsHistoricalDigestContractTest::historicalEmpty)
                .perform(
                        get("/market-news/digests")
                                .param("from", "2026-08-01T00:00:00Z")
                                .param("to", "2026-08-31T00:00:00Z"))
                .andExpect(jsonPath("$.result.context").value("HISTORICAL"))
                .andExpect(jsonPath("$.result.from").value("2026-08-01T00:00:00Z"))
                .andExpect(jsonPath("$.result.to").value("2026-08-31T00:00:00Z"));
    }
}
