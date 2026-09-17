package com.ssafy.thorp.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.learning.support.LearningSeeds;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 배우기 공개 조회 세 주소를 실제로 호출한다(learning-public-api, quickstart 2-1·2-2·3-5·4-4 서버 쪽).
 *
 * <p><b>응답이 {@code docs/specs/learning-api/examples/}의 예시 파일과 JSON 동치인지 단언한다.</b> 계약이 구현과 어긋나면 여기서
 * 깨진다.
 *
 * <p>게시 유스케이스(B02)가 아직 없어 판·색인·글 상태를 {@link LearningSeeds}가 SQL로 심는다. 심는 값은 예시 파일에서 거꾸로 만든다. 그래서
 * 예시를 바꾸면 심는 판도 함께 바뀌고, 비교가 스스로를 확인하는 것이 아니라 "판 → 응답" 변환을 확인한다.
 *
 * <p><b>Spring 컨텍스트를 새로 만들지 않는다.</b> {@code @SpringBootTest} 속성을 {@code SectorApiIT}와 글자 그대로 같게 두어
 * 캐시된 컨텍스트를 재사용한다. 속성 하나라도 바꾸면 컨텍스트가 갈라져 공용 컨테이너의 연결 수가 모자라게 된다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class LearningPublicApiIT extends PostgresTestSupport {

    /** gradle test의 작업 디렉터리는 backend/다. */
    private static final Path EXAMPLES = Path.of("..", "docs", "specs", "learning-api", "examples");

    private static final String ARTICLE = "returns-and-losses";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private LearningSeeds seeds;

    @BeforeEach
    void setUp() {
        seeds = new LearningSeeds(jdbcTemplate, objectMapper);
        seeds.reset();
    }

    @AfterEach
    void tearDown() {
        seeds.reset();
    }

    private JsonNode example(String file) throws Exception {
        return objectMapper.readTree(Files.readString(EXAMPLES.resolve(file)));
    }

    private JsonNode call(String url, int expectedStatus) throws Exception {
        String body =
                mockMvc.perform(get(url))
                        .andExpect(status().is(expectedStatus))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private JsonNode data(String url) throws Exception {
        JsonNode body = call(url, 200);
        assertThat(body.get("success").asBoolean()).isTrue();
        return body.get("data");
    }

    private void assertErrorCode(String url, String code) throws Exception {
        JsonNode body = call(url, 404);
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.at("/error/code").asText()).isEqualTo(code);
    }

    /** 예시의 두 섹터가 모두 있는 대응표. */
    private void seedFullSectorMap() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("SEMICONDUCTOR", "반도체");
        names.put("BATTERY", "2차전지");
        seeds.seedSectorMap(names);
    }

    /** articles-list.json의 두 글을 심는다. 수익과 손실은 article.json 전체로 심는다. */
    private void publishListFixture() throws Exception {
        JsonNode list = example("articles-list.json").get("articles");
        JsonNode first = list.get(0);
        seeds.publishMinimal(
                first.get("articleId").asText(),
                first.get("title").asText(),
                first.get("question").asText(),
                first.get("readingOrder").asInt());
        JsonNode article = example("article.json");
        seeds.publishFromResponse(article, article.get("readingOrder").asInt());
    }

    // --- §1 목록 ---

    @Test
    @DisplayName("게시된 글이 없으면 200 articles: []다 (quickstart 2-1)")
    void emptyListBeforePublishing() throws Exception {
        JsonNode data = data("/learning/articles");

        assertThat(data.get("articles").isArray()).isTrue();
        assertThat(data.get("articles")).isEmpty();
    }

    @Test
    @DisplayName("목록 응답이 articles-list.json과 같다")
    void listMatchesExample() throws Exception {
        seedFullSectorMap();
        publishListFixture();

        assertThat(data("/learning/articles")).isEqualTo(example("articles-list.json"));
    }

    @Test
    @DisplayName("목록은 판의 readingOrder, articleId 순이고 초안 컬럼의 순서를 따르지 않는다")
    void listIsOrderedByRevisionReadingOrderThenArticleId() throws Exception {
        // 넣는 차례를 기대하는 차례와 다르게 둔다. 초안 reading_order는 backtest 6, transaction-cost 7,
        // sectors-and-etf 1이지만 판에는 1·1·2를 준다 — 초안 컬럼으로 정렬하면 순서가 달라진다.
        seeds.publishMinimal("transaction-cost", "거래비용", "q", 2);
        seeds.publishMinimal("sectors-and-etf", "섹터와 ETF", "q", 1);
        seeds.publishMinimal("backtest", "백테스트", "q", 1);

        JsonNode articles = data("/learning/articles").get("articles");

        assertThat(articles)
                .extracting(item -> item.get("articleId").asText())
                .containsExactly("backtest", "sectors-and-etf", "transaction-cost");
    }

    // --- §2 글 하나 ---

    @Test
    @DisplayName("초안뿐인 글은 목록에 없고 단건은 404 LEARNING_ARTICLE_UNAVAILABLE이다 (quickstart 2-2, SC-006)")
    void draftArticleIsUnavailable() throws Exception {
        seeds.publishMinimal("sectors-and-etf", "섹터와 ETF", "q", 1);

        assertThat(data("/learning/articles").get("articles"))
                .extracting(item -> item.get("articleId").asText())
                .doesNotContain(ARTICLE);
        assertErrorCode("/learning/articles/" + ARTICLE, "LEARNING_ARTICLE_UNAVAILABLE");
    }

    @Test
    @DisplayName("없는 글과 형식이 틀린 articleId도 같은 404다")
    void missingAndInvalidArticleIdsAreUnavailable() throws Exception {
        assertErrorCode("/learning/articles/no-such-article", "LEARNING_ARTICLE_UNAVAILABLE");
        assertErrorCode("/learning/articles/Bad_Key", "LEARNING_ARTICLE_UNAVAILABLE");
    }

    @Test
    @DisplayName("글 응답이 article.json과 같다")
    void articleMatchesExample() throws Exception {
        seedFullSectorMap();
        publishListFixture();

        assertThat(data("/learning/articles/" + ARTICLE)).isEqualTo(example("article.json"));
    }

    @Test
    @DisplayName("게시 뒤 초안 용어 문장을 바꿔도 글·용어 응답의 문장은 판의 값 그대로다 (SC-006, quickstart 3-5)")
    void draftTermChangesDoNotLeakIntoPublicResponses() throws Exception {
        seedFullSectorMap();
        publishListFixture();
        String published = example("term.json").get("sentence").asText();

        // 초안의 이름·제목·순서는 seeds.reset()이 되돌리지 않으므로 이 테스트가 직접 되돌린다.
        // 마이그레이션 직후 값을 기대하는 LearningMigrationTest와 DB를 공유한다.
        jdbcTemplate.update(
                "UPDATE learning_term SET sentence = '게시하지 않은 초안 문장', label = '초안 이름'"
                        + " WHERE term_key = 'max-drawdown'");
        jdbcTemplate.update(
                "UPDATE learning_article SET title = '초안 제목', reading_order = 1"
                        + " WHERE article_key = ?",
                ARTICLE);
        try {
            JsonNode article = data("/learning/articles/" + ARTICLE);
            assertThat(article.get("title").asText()).isEqualTo("수익과 손실");
            assertThat(article.get("readingOrder").asInt()).isEqualTo(8);
            assertThat(termBlock(article).get("label").asText()).isEqualTo("가장 크게 줄었던 폭");
            assertThat(termBlock(article).get("sentence").asText()).isEqualTo(published);
            assertThat(data("/learning/terms/max-drawdown").get("sentence").asText())
                    .isEqualTo(published);
        } finally {
            jdbcTemplate.update(
                    "UPDATE learning_term SET label = '가장 크게 줄었던 폭'"
                            + " WHERE term_key = 'max-drawdown'");
            jdbcTemplate.update(
                    "UPDATE learning_article SET title = '수익과 손실', reading_order = 8"
                            + " WHERE article_key = ?",
                    ARTICLE);
        }
    }

    @Test
    @DisplayName("대응표가 없으면 200에 available=false, SECTOR_MAP_NOT_READY, 이름 null이다")
    void exampleUnavailableWhenMapNotReady() throws Exception {
        publishListFixture();

        JsonNode example = data("/learning/articles/" + ARTICLE).get("example");

        assertThat(example.get("available").asBoolean()).isFalse();
        assertThat(example.get("unavailableReason").asText()).isEqualTo("SECTOR_MAP_NOT_READY");
        assertThat(example.get("sectors"))
                .allSatisfy(sector -> assertThat(sector.get("name").isNull()).isTrue());
        assertThat(example.get("sectors"))
                .extracting(sector -> sector.get("weightTenths").asInt())
                .containsExactly(500, 500);
    }

    /**
     * 저장된 판의 값이 깨졌을 때 공개 조회는 서버 오류다. 요청은 올바르므로 400 {@code INVALID_INPUT}으로 답하면 클라이언트 탓이 되고, 로그도
     * WARN으로 남아 운영자가 알아채지 못한다. 응답 메시지에 내부 값을 싣지 않는다. 이 동작을 고정해, 변환 위치가 {@code @Repository} 밖으로 옮겨져도
     * 400으로 바뀌지 않게 한다.
     */
    @Test
    @DisplayName("판에 저장된 값이 도메인 규칙을 어기면 400이 아니라 500 INTERNAL_ERROR다")
    void corruptStoredValueIsServerError() throws Exception {
        seedFullSectorMap();
        com.fasterxml.jackson.databind.node.ObjectNode article =
                (com.fasterxml.jackson.databind.node.ObjectNode) example("article.json");
        ((com.fasterxml.jackson.databind.node.ObjectNode) article.at("/example/sectors/1"))
                .put("weightTenths", 1500);
        seeds.publishFromResponse(article, 8);

        JsonNode body = call("/learning/articles/" + ARTICLE, 500);

        assertThat(body.at("/error/code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.at("/error/message").asText()).doesNotContain("1500");
    }

    @Test
    @DisplayName("대응표에서 코드 하나가 빠지면 SECTOR_NOT_IN_MAP과 빠진 코드다 (quickstart 4-4 서버 쪽)")
    void exampleUnavailableWhenSectorMissing() throws Exception {
        seeds.seedSectorMap(Map.of("SEMICONDUCTOR", "반도체"));
        publishListFixture();

        JsonNode example = data("/learning/articles/" + ARTICLE).get("example");

        assertThat(example.get("available").asBoolean()).isFalse();
        assertThat(example.get("unavailableReason").asText()).isEqualTo("SECTOR_NOT_IN_MAP");
        assertThat(example.get("missingSectorCodes"))
                .extracting(JsonNode::asText)
                .containsExactly("BATTERY");
    }

    // --- §3 용어 ---

    /**
     * 두 번째 단언은 SC-003의 증명이 아니다. {@link LearningSeeds}가 색인과 판의 용어를 같은 값으로 함께 심으므로 여기서는 늘 참이다. 두 경로가
     * 같은 문장을 돌려주도록 "응답 조립"이 서로 다른 원천을 섞지 않는지만 본다. 게시 경로를 거친 SC-003 확인은 B02의 {@code
     * LearningAdminApiIT}가 한다.
     */
    @Test
    @DisplayName("게시된 용어 응답이 term.json과 같고, 글의 term 블록과 같은 문장을 돌려준다")
    void termMatchesExampleAndArticleBlock() throws Exception {
        seedFullSectorMap();
        publishListFixture();

        JsonNode term = data("/learning/terms/max-drawdown");

        assertThat(term).isEqualTo(example("term.json"));
        assertThat(term.get("sentence"))
                .isEqualTo(termBlock(data("/learning/articles/" + ARTICLE)).get("sentence"));
    }

    @Test
    @DisplayName("초안뿐인 용어, 없는 용어, 형식이 틀린 용어는 모두 404 LEARNING_TERM_NOT_READY다 (quickstart 2-2)")
    void unpublishedTermsAreNotReady() throws Exception {
        seedFullSectorMap();
        publishListFixture();

        assertErrorCode("/learning/terms/rebalancing", "LEARNING_TERM_NOT_READY");
        assertErrorCode("/learning/terms/no-such-term", "LEARNING_TERM_NOT_READY");
        assertErrorCode("/learning/terms/Bad_Term", "LEARNING_TERM_NOT_READY");
    }

    @Test
    @DisplayName("색인 행을 지우면(게시 취소의 서버 결과) 같은 404다")
    void termIsNotReadyAfterIndexIsCleared() throws Exception {
        seedFullSectorMap();
        publishListFixture();
        data("/learning/terms/max-drawdown");

        jdbcTemplate.update("DELETE FROM learning_published_term WHERE term_key = 'max-drawdown'");

        assertErrorCode("/learning/terms/max-drawdown", "LEARNING_TERM_NOT_READY");
    }

    private static JsonNode termBlock(JsonNode article) {
        for (JsonNode block : article.get("blocks")) {
            if ("term".equals(block.get("type").asText())) {
                return block;
            }
        }
        throw new AssertionError("no term block in article response");
    }
}
