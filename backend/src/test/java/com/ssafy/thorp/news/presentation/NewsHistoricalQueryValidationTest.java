package com.ssafy.thorp.news.presentation;

import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.mockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import com.ssafy.thorp.news.domain.vo.NewsDigestState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 과거 조회 요청의 유효성 판정(spec FR-006, T029).
 *
 * <p>여기서 400이 되는 것은 <b>사용자가 요청을 고쳐 다시 부를 수 있는 경우뿐</b>이다. 자료가 없는 것은 오류가 아니라 빈 화면이므로 이 시험의 어느 항목도 자료
 * 유무를 다루지 않는다.
 *
 * <p>경계를 포함한다는 것이 중요하다. 정확히 상한 길이인 구간을 거부하면 화면이 "최대 93일"이라고 안내해 놓고 93일을 받지 않는 상태가 된다.
 */
class NewsHistoricalQueryValidationTest {

    private static final Instant FROM = Instant.parse("2026-07-01T00:00:00Z");

    private static NewsDigestListResult empty(NewsDigestQuery query) {
        return new NewsDigestListResult(
                query.context(),
                NewsDigestState.EMPTY,
                query.sectorId(),
                query.from(),
                query.to(),
                null,
                false,
                "해당 기간에 표시할 수 있는 당시 뉴스가 없습니다.",
                List.of());
    }

    @Nested
    @DisplayName("기간 쌍")
    class Pairing {

        @Test
        @DisplayName("from만 주면 거부한다")
        void from만() throws Exception {
            mockMvc(NewsHistoricalQueryValidationTest::empty)
                    .perform(get("/market-news/digests").param("from", FROM.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.isSuccess").value(false))
                    .andExpect(jsonPath("$.result").doesNotExist());
        }

        @Test
        @DisplayName("to만 주면 거부한다")
        void to만() throws Exception {
            mockMvc(NewsHistoricalQueryValidationTest::empty)
                    .perform(get("/market-news/digests").param("to", FROM.toString()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("둘 다 주지 않으면 현재 조회다")
        void 둘_다_없음() throws Exception {
            mockMvc(NewsHistoricalQueryValidationTest::empty)
                    .perform(get("/market-news/digests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.context").value("CURRENT"));
        }
    }

    @Nested
    @DisplayName("구간")
    class Range {

        @Test
        @DisplayName("시작이 끝보다 늦으면 거부한다")
        void 역전() throws Exception {
            mockMvc(NewsHistoricalQueryValidationTest::empty)
                    .perform(
                            get("/market-news/digests")
                                    .param("from", "2026-09-10T00:00:00Z")
                                    .param("to", "2026-09-01T00:00:00Z"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("같은 순간이면 받는다")
        void 한_점() throws Exception {
            mockMvc(NewsHistoricalQueryValidationTest::empty)
                    .perform(
                            get("/market-news/digests")
                                    .param("from", FROM.toString())
                                    .param("to", FROM.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.context").value("HISTORICAL"));
        }

        @Test
        @DisplayName("정확히 상한 길이인 구간은 받는다")
        void 상한_경계() throws Exception {
            mockMvc(NewsHistoricalQueryValidationTest::empty)
                    .perform(
                            get("/market-news/digests")
                                    .param("from", FROM.toString())
                                    .param(
                                            "to",
                                            FROM.plus(
                                                            java.time.Duration.ofDays(
                                                                    NewsContractTestSupport
                                                                            .MAX_RANGE_DAYS))
                                                    .toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("상한을 1초라도 넘으면 거부한다")
        void 상한_초과() throws Exception {
            mockMvc(NewsHistoricalQueryValidationTest::empty)
                    .perform(
                            get("/market-news/digests")
                                    .param("from", FROM.toString())
                                    .param(
                                            "to",
                                            FROM.plus(
                                                            java.time.Duration.ofDays(
                                                                    NewsContractTestSupport
                                                                            .MAX_RANGE_DAYS))
                                                    .plusSeconds(1)
                                                    .toString()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("시각 표기")
    class TimeFormat {

        @Test
        @DisplayName("시간대 오프셋 표기도 같은 순간으로 읽는다")
        void 오프셋() throws Exception {
            // 2026-07-01T09:00:00+09:00 == 2026-07-01T00:00:00Z
            mockMvc(
                            query -> {
                                org.assertj.core.api.Assertions.assertThat(query.from())
                                        .as("표기가 달라도 같은 순간이면 같은 결과여야 한다")
                                        .isEqualTo(FROM);
                                return empty(query);
                            })
                    .perform(
                            get("/market-news/digests")
                                    .param("from", "2026-07-01T09:00:00+09:00")
                                    .param("to", "2026-07-02T09:00:00+09:00"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("해석할 수 없는 시각은 거부하고 받은 값을 되비추지 않는다")
        void 깨진_표기() throws Exception {
            mockMvc(NewsHistoricalQueryValidationTest::empty)
                    .perform(
                            get("/market-news/digests")
                                    .param("from", "2026-07-01")
                                    .param("to", "어제"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("시각은 ISO-8601 형식이어야 합니다."));
        }
    }
}
