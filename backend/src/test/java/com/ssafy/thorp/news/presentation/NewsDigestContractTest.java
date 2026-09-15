package com.ssafy.thorp.news.presentation;

import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.MAPPER;
import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.example;
import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.mockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import com.ssafy.thorp.news.domain.vo.NewsDigestState;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /market-news/digests}의 READY 응답이 계약 예시와 같은지 본다(spec FR-001, T015).
 *
 * <p>계약 예시 파일을 그대로 읽어 대조한다. 예시를 시험 안에 베껴 적으면 계약이 바뀌었을 때 두 곳을 고쳐야 하고, 고치지 않은 쪽이 남아 시험은 통과하는데 화면은 깨지는
 * 상태가 된다.
 *
 * <p><b>세 묶음이 안 되면 채우지 않는다.</b> 이것이 이 시험의 두 번째 초점이다. 개수를 맞추려고 관련 없는 기사를 끌어오면 화면은 그것을 '주요 뉴스'로 읽는다
 * (원칙 VI).
 */
class NewsDigestContractTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-10T04:00:00Z");
    private static final Instant SECOND_PUBLISHED_AT = Instant.parse("2026-09-10T04:20:00Z");
    private static final Instant LAST_UPDATED_AT = Instant.parse("2026-09-10T06:15:00Z");

    /** contracts/examples/current-ready.json에 실린 묶음 그대로다. */
    private static NewsDigestListResult.Digest contractDigest() {
        return new NewsDigestListResult.Digest(
                "2356p2tnowwutogqkjlretuvevwid7lg",
                "반도체 수출 관련 지표가 발표됐다",
                "등록 매체들이 반도체 수출 관련 지표 발표를 보도했습니다.",
                PUBLISHED_AT,
                PublicationTimeStatus.VERIFIED,
                2,
                List.of("semiconductor"),
                List.of("trade"),
                "news-importance-v1",
                List.of(
                        new NewsDigestListResult.Article(
                                "jzxp4sxkitcyjow6mtiywps42u3kr6co",
                                "반도체 수출 관련 지표가 발표됐다",
                                "허가 매체 A",
                                PUBLISHED_AT,
                                PublicationTimeStatus.VERIFIED,
                                "https://news.example.com/economy/1001"),
                        new NewsDigestListResult.Article(
                                "2pkcdwrcoebpa2igzaxldxloi5qooglf",
                                "반도체 수출 지표 발표",
                                "허가 매체 B",
                                SECOND_PUBLISHED_AT,
                                PublicationTimeStatus.VERIFIED,
                                "https://press.example.org/markets/2002")));
    }

    private static NewsDigestListResult ready() {
        return new NewsDigestListResult(
                NewsDigestQuery.Context.CURRENT,
                NewsDigestState.READY,
                "semiconductor",
                null,
                null,
                LAST_UPDATED_AT,
                false,
                null,
                List.of(contractDigest()));
    }

    @Test
    @DisplayName("READY 응답이 계약 예시와 글자 그대로 같다")
    void 계약_예시_일치() throws Exception {
        String body =
                mockMvc(query -> ready())
                        .perform(get("/market-news/digests").param("sectorId", "semiconductor"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(MAPPER.readTree(body))
                .as("계약 예시가 기준이다. 화면은 이 모양을 보고 만들어졌다")
                .isEqualTo(example("current-ready.json"));
    }

    @Test
    @DisplayName("묶음이 세 개가 안 되면 있는 만큼만 돌려준다")
    void 부족하면_채우지_않는다() throws Exception {
        mockMvc(query -> ready())
                .perform(get("/market-news/digests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.digests.length()").value(1))
                .andExpect(jsonPath("$.result.state").value("READY"));
    }

    @Test
    @DisplayName("기사 본문을 담는 자리가 응답 어디에도 없다")
    void 본문_없음() throws Exception {
        String body =
                mockMvc(query -> ready())
                        .perform(get("/market-news/digests"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        JsonNode article = MAPPER.readTree(body).at("/result/digests/0/articles/0");
        assertThat(article.fieldNames())
                .toIterable()
                .as("본문 필드가 스키마에 있으면 언젠가 채워진다(spec FR-002)")
                .doesNotContain("body", "content", "description", "summary");
    }

    @Test
    @DisplayName("기간을 주지 않으면 현재 조회다")
    void 기본은_현재_조회() throws Exception {
        mockMvc(
                        query -> {
                            assertThat(query.isHistorical()).isFalse();
                            return ready();
                        })
                .perform(get("/market-news/digests"))
                .andExpect(jsonPath("$.result.context").value("CURRENT"))
                .andExpect(jsonPath("$.result.from").doesNotExist())
                .andExpect(jsonPath("$.result.to").doesNotExist());
    }
}
