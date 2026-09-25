package com.ssafy.thorp.news.presentation;

import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.MAPPER;
import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.example;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.thorp.news.application.command.NewsArticleExportQuery;
import com.ssafy.thorp.news.application.result.NewsArticleExportResult;
import com.ssafy.thorp.news.application.service.NewsArticleExportService;
import com.ssafy.thorp.news.application.service.NewsDigestQueryService;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import com.ssafy.thorp.news.presentation.controller.NewsArticleExportExceptionHandler;
import com.ssafy.thorp.news.presentation.controller.NewsDigestController;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code GET /market-news/articles/export}의 응답이 계약과 같은지 본다(spec FR-017·SC-008, T047).
 *
 * <p>가장 중요한 것은 <b>허용되지 않은 필드가 키째로 빠지는 것</b>이다. {@code null}로 실어 보내면 "매체가 값을 주지 않았다"와 "우리가 내보낼 권한이
 * 없다"가 같은 모양이 되고, 받는 쪽은 그 둘을 구별하지 못한 채 빈 값을 저장한다.
 */
class NewsArticleExportContractTest {

    private static MockMvc mockMvc(Function<NewsArticleExportQuery, NewsArticleExportResult> stub) {
        return MockMvcBuilders.standaloneSetup(
                        new NewsDigestController(null, new StubExportService(stub), null))
                .setControllerAdvice(new NewsArticleExportExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
                .build();
    }

    /** contracts/examples/export-allowed.json에 실린 항목 그대로다. */
    private static NewsArticleExportResult contractResult() {
        return new NewsArticleExportResult(
                List.of(
                        new NewsArticleExportResult.Item(
                                "jhx337tiywd4p2svejdapwk7kaeyqr36",
                                "반도체 수출 지표가 발표됐다",
                                "허가 매체",
                                Instant.parse("2026-09-10T01:00:00Z"),
                                PublicationTimeStatus.VERIFIED,
                                "https://example.com/economy/semiconductor-export")),
                null);
    }

    @Test
    @DisplayName("허용된 항목의 응답이 계약 예시와 같다")
    void 계약_예시_일치() throws Exception {
        String body =
                mockMvc(query -> contractResult())
                        .perform(
                                get("/market-news/articles/export")
                                        .param("from", "2026-09-01T00:00:00Z")
                                        .param("to", "2026-09-30T00:00:00Z"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(MAPPER.readTree(body)).isEqualTo(example("export-allowed.json"));
    }

    @Test
    @DisplayName("허용되지 않은 필드는 키째로 빠진다")
    void 미허용_필드는_키가_없다() throws Exception {
        NewsArticleExportResult onlyTitle =
                new NewsArticleExportResult(
                        List.of(
                                new NewsArticleExportResult.Item(
                                        "jhx337tiywd4p2svejdapwk7kaeyqr36",
                                        "제목만 허용된 기사",
                                        null,
                                        null,
                                        PublicationTimeStatus.UNKNOWN,
                                        null)),
                        null);

        String body =
                mockMvc(query -> onlyTitle)
                        .perform(
                                get("/market-news/articles/export")
                                        .param("from", "2026-09-01T00:00:00Z")
                                        .param("to", "2026-09-30T00:00:00Z"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        JsonNode item = MAPPER.readTree(body).at("/result/items/0");
        assertThat(item.fieldNames())
                .toIterable()
                .as("빈 값으로 실어 보내면 받는 쪽이 그것을 저장한다")
                .containsExactlyInAnyOrder("articleId", "title", "publicationTimeStatus");
    }

    @Test
    @DisplayName("기사 본문을 담는 자리가 없다")
    void 본문_없음() throws Exception {
        String body =
                mockMvc(query -> contractResult())
                        .perform(
                                get("/market-news/articles/export")
                                        .param("from", "2026-09-01T00:00:00Z")
                                        .param("to", "2026-09-30T00:00:00Z"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        JsonNode item = MAPPER.readTree(body).at("/result/items/0");
        assertThat(item.fieldNames())
                .toIterable()
                .as("허가 여부와 무관하게 본문은 저장도 반환도 하지 않는다(spec FR-002)")
                .doesNotContain("body", "content", "description", "summary");
    }

    @Test
    @DisplayName("기간을 주지 않으면 400이다")
    void 기간_필수() throws Exception {
        mockMvc(query -> contractResult())
                .perform(get("/market-news/articles/export"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    /** 내보내기 서비스 대역. 컨트롤러가 계약대로 옮기는지만 본다. */
    private static final class StubExportService extends NewsArticleExportService {

        private final Function<NewsArticleExportQuery, NewsArticleExportResult> stub;

        private StubExportService(Function<NewsArticleExportQuery, NewsArticleExportResult> stub) {
            super(
                    null,
                    null,
                    null,
                    null,
                    new NewsDigestQueryService.NewsQuerySettings(null, null, 3, null, 93));
            this.stub = stub;
        }

        @Override
        public NewsArticleExportResult export(
                String sectorId, Instant from, Instant to, String cursor, Integer limit) {
            return stub.apply(NewsArticleExportQuery.of(sectorId, from, to, cursor, limit, 93));
        }
    }
}
