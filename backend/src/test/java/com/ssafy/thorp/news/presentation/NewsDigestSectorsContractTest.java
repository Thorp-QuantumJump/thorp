package com.ssafy.thorp.news.presentation;

import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.mockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import com.ssafy.thorp.news.domain.vo.NewsDigestState;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /market-news/digests}가 섹터 여럿과 {@code limit}을 받는지 본다(spec FR-001 ③, 2026-10-02).
 *
 * <p>투자 대상 고르기의 '분류별 뉴스'는 분류에 배정된 섹터들의 묶음을 한 번에 받는다. 화면이 섹터마다 따로 받아 합치면 주요도 규칙(FR-004)을 화면이 한 벌 더
 * 갖게 되므로, 합치는 일은 서버가 한다. 여기서는 요청이 조회 객체로 옮겨지는 모양만 본다. 합친 결과의 순서는 조회 서비스 시험이 본다.
 */
class NewsDigestSectorsContractTest {

    private static NewsDigestListResult empty(NewsDigestQuery query) {
        return new NewsDigestListResult(
                query.context(),
                NewsDigestState.EMPTY,
                query.sectorId(),
                null,
                null,
                null,
                false,
                null,
                List.of());
    }

    @Test
    @DisplayName("섹터를 여러 번 주면 모두 조회 객체로 옮기고, 응답의 sectorId는 비운다")
    void 섹터_여럿을_반복해서_받는다() throws Exception {
        AtomicReference<NewsDigestQuery> seen = new AtomicReference<>();
        mockMvc(
                        query -> {
                            seen.set(query);
                            return empty(query);
                        })
                .perform(
                        get("/market-news/digests")
                                .param("sectorId", "NEWS_IT", "SEMICONDUCTOR")
                                .param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.sectorId").value(nullValue()));

        assertThat(seen.get().sectorIds()).containsExactly("NEWS_IT", "SEMICONDUCTOR");
        assertThat(seen.get().limit()).isEqualTo(3);
    }

    @Test
    @DisplayName("쉼표로 이어 준 섹터도 같은 뜻이다")
    void 쉼표로_이어도_같다() throws Exception {
        AtomicReference<NewsDigestQuery> seen = new AtomicReference<>();
        mockMvc(
                        query -> {
                            seen.set(query);
                            return empty(query);
                        })
                .perform(get("/market-news/digests").param("sectorId", "NEWS_IT,SEMICONDUCTOR"))
                .andExpect(status().isOk());

        assertThat(seen.get().sectorIds()).containsExactly("NEWS_IT", "SEMICONDUCTOR");
        assertThat(seen.get().limit()).as("limit을 주지 않으면 비워 두고 조회가 예전 개수(3)를 쓴다").isNull();
    }

    @Test
    @DisplayName("섹터 하나는 예전처럼 응답의 sectorId에 그대로 나간다")
    void 섹터_하나는_그대로다() throws Exception {
        mockMvc(NewsDigestSectorsContractTest::empty)
                .perform(get("/market-news/digests").param("sectorId", "SEMICONDUCTOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.sectorId").value("SEMICONDUCTOR"));
    }

    @Test
    @DisplayName("limit이 1보다 작으면 400이다")
    void limit은_1_이상() throws Exception {
        mockMvc(NewsDigestSectorsContractTest::empty)
                .perform(get("/market-news/digests").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("limit을 정수로 읽지 못하면 시각 문장이 아니라 개수 문장으로 400이다")
    void limit_형식() throws Exception {
        mockMvc(NewsDigestSectorsContractTest::empty)
                .perform(get("/market-news/digests").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit은 1 이상의 정수여야 합니다."));
    }

    @Test
    @DisplayName("과거 구간은 섹터 하나까지만 받는다")
    void 과거_구간은_섹터_하나() throws Exception {
        mockMvc(NewsDigestSectorsContractTest::empty)
                .perform(
                        get("/market-news/digests")
                                .param("sectorId", "NEWS_IT", "SEMICONDUCTOR")
                                .param("from", "2026-09-01T00:00:00Z")
                                .param("to", "2026-09-10T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }
}
