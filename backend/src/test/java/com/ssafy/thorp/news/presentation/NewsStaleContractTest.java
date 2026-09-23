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
 * 수집이 지연됐을 때의 응답(spec FR-014·FR-024, T036).
 *
 * <p><b>지연은 오류가 아니다.</b> HTTP 200으로 상태를 실어 보내고 화면은 마지막 갱신 시각을 함께 보인다. 여기가 500이 되면 외부 뉴스원의 사정이 시장 흐름
 * 화면 전체를 내려 버린다.
 *
 * <p>준비 중(PREPARING) 응답은 {@code NewsPreparingContractTest}가 본다. 지연과 준비 중은 원인도 화면이 할 일도 달라 한 파일에 두면
 * 무엇을 고정하려는 파일인지 흐려진다.
 */
class NewsStaleContractTest {

    private static NewsDigestListResult stale() {
        return new NewsDigestListResult(
                NewsDigestQuery.Context.CURRENT,
                NewsDigestState.STALE,
                null,
                null,
                null,
                Instant.parse("2026-09-10T04:00:00Z"),
                true,
                "뉴스 수집이 지연되어 마지막 갱신 자료를 표시합니다.",
                List.of());
    }

    @Test
    @DisplayName("STALE 응답이 계약 예시와 같다")
    void 계약_예시_일치() throws Exception {
        String body =
                mockMvc(query -> stale())
                        .perform(get("/market-news/digests"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(MAPPER.readTree(body)).isEqualTo(example("current-stale.json"));
    }

    @Test
    @DisplayName("지연은 오류가 아니라 200이다")
    void 지연도_정상_화면() throws Exception {
        mockMvc(query -> stale())
                .perform(get("/market-news/digests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.stale").value(true))
                .andExpect(jsonPath("$.result.lastUpdatedAt").value("2026-09-10T04:00:00Z"));
    }
}
