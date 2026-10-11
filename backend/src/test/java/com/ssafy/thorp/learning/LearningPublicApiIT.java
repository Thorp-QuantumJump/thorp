package com.ssafy.thorp.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thorp.learning.support.LearningSeeds;
import com.ssafy.thorp.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 배우기 공개 조회 주소(글 하나·용어 하나)와 지운 9월 판 목록 경로를 실제로 호출한다(learning-public-api, quickstart
 * 2-2·3-5·4-4·6-1·9-2 서버 쪽).
 *
 * <p><b>응답이 {@code docs/specs/learning-api/examples/}의 예시 파일과 JSON 동치인지 단언한다.</b> 계약이 구현과 어긋나면 여기서
 * 깨진다. 9월 판 글 응답 {@code article.json}만은 2판에서 필드가 더해졌으므로 응답 가운데 그 파일에 있는 키만 골라 비교한다({@link
 * #onlyKeysOf}). 9월 판 필드의 모양과 값이 그대로인지 보는 배포 순서 호환 확인이다. 2판 글 응답은 {@code edition}마다 파일이 따로 있고 전체를
 * 비교한다.
 *
 * <p>게시 유스케이스(B02)가 아직 없어 판·색인·글 상태를 {@link LearningSeeds}가 SQL로 심는다. 심는 값은 예시 파일에서 거꾸로 만든다. 그래서
 * 예시를 바꾸면 심는 판도 함께 바뀌고, 비교가 스스로를 확인하는 것이 아니라 "판 → 응답" 변환을 확인한다.
 *
 * <p><b>Spring 컨텍스트를 새로 만들지 않는다.</b> {@link IntegrationTestSupport}의 설정을 그대로 물려받아 캐시된 컨텍스트를 재사용한다.
 */
class LearningPublicApiIT extends IntegrationTestSupport {

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

    /** 9월 판의 두 글을 심는다. 섹터와 ETF는 최소 판, 수익과 손실은 article.json 전체로 심는다. */
    private void publishListFixture() throws Exception {
        seeds.publishMinimal("sectors-and-etf", "섹터와 ETF", "섹터와 대표 ETF는 무엇이 같고 무엇이 다른가?", 1);
        JsonNode article = example("article.json");
        seeds.publishFromResponse(article, article.get("readingOrder").asInt());
    }

    // --- 9월 판 목록(지웠다) ---

    @Test
    @DisplayName("9월 판 목록 경로는 지웠다 — 글이 게시돼 있어도 404다 (B16)")
    void legacyListRouteIsGone() throws Exception {
        publishListFixture();

        mockMvc.perform(get("/learning/articles")).andExpect(status().isNotFound());
    }

    // --- §2 글 하나 ---

    @Test
    @DisplayName("초안뿐인 글은 404 LEARNING_ARTICLE_UNAVAILABLE이다 (quickstart 2-2, SC-006)")
    void draftArticleIsUnavailable() throws Exception {
        seeds.publishMinimal("sectors-and-etf", "섹터와 ETF", "q", 1);

        assertErrorCode("/learning/articles/" + ARTICLE, "LEARNING_ARTICLE_UNAVAILABLE");
    }

    @Test
    @DisplayName("없는 글과 형식이 틀린 articleId도 같은 404다")
    void missingAndInvalidArticleIdsAreUnavailable() throws Exception {
        assertErrorCode("/learning/articles/no-such-article", "LEARNING_ARTICLE_UNAVAILABLE");
        assertErrorCode("/learning/articles/Bad_Key", "LEARNING_ARTICLE_UNAVAILABLE");
    }

    @Test
    @DisplayName("9월 판 필드는 article.json과 같다 (2판 필드는 더해질 뿐이다)")
    void articleMatchesExample() throws Exception {
        seedFullSectorMap();
        publishListFixture();

        JsonNode expected = example("article.json");
        assertThat(onlyKeysOf(data("/learning/articles/" + ARTICLE), expected)).isEqualTo(expected);
    }

    @Test
    @DisplayName("단계를 하나도 공개하지 않았으면 LEGACY 판은 article-pre-release.json과 같다 (quickstart 6-1)")
    void preReleaseArticleMatchesExample() throws Exception {
        seedFullSectorMap();
        publishListFixture();

        assertThat(data("/learning/articles/" + ARTICLE))
                .isEqualTo(example("article-pre-release.json"));
    }

    @Test
    @DisplayName("공개된 단계가 있으면 LEGACY 판은 article-previous.json과 같다 (quickstart 9-5 서버 쪽)")
    void previousArticleMatchesExample() throws Exception {
        seedFullSectorMap();
        publishListFixture();
        seeds.markStageReleased("etf-market-basics");

        assertThat(data("/learning/articles/" + ARTICLE))
                .isEqualTo(example("article-previous.json"));
    }

    /**
     * {@code article-current.json}의 상태를 심는다. 1~3단계를 공개하고, 2단계에 이 글 앞의 세 글과 3단계 첫 글을 최소 CURRENT 판으로
     * 게시한다. {@code returns-and-losses}가 {@code max-drawdown}을 다른 이름과 함께 게시한다. {@code rebalancing}은
     * 게시하지 않는다.
     */
    private void publishCurrentFixture() throws Exception {
        seeds.markStageReleased("etf-market-basics");
        seeds.markStageReleased("returns-and-risk");
        seeds.markStageReleased("allocation-rules");
        seeds.publishCurrentMinimal(
                "returns-and-losses",
                "수익과 손실",
                Map.of("max-drawdown", List.of("최대 낙폭", "MDD", "maximum drawdown")));
        seeds.publishCurrentMinimal("same-return-different-feel", "같은 수익률인데 왜 느낌이 다를까?", Map.of());
        seeds.publishCurrentMinimal("recovery-period", "떨어진 뒤 얼마나 걸려 돌아왔을까?", Map.of());
        seeds.publishCurrentMinimal("weights-and-rebalancing", "비중과 리밸런싱", Map.of());
        seeds.publishCurrentFromResponse(example("article-current.json"));
    }

    @Test
    @DisplayName("CURRENT 글 응답이 article-current.json과 같다 (V2 블록·번호·다음 글·언급의 다른 이름)")
    void currentArticleMatchesExample() throws Exception {
        publishCurrentFixture();

        assertThat(data("/learning/articles/volatility-is-not-loss"))
                .isEqualTo(example("article-current.json"));
    }

    @Test
    @DisplayName("다른 글 용어의 다른 이름은 게시 색인에서 오고, 그 색인이 없으면 빈 배열이다 (quickstart 9-2 서버 쪽)")
    void foreignMentionAliasesComeFromIndex() throws Exception {
        publishCurrentFixture();
        jdbcTemplate.update("DELETE FROM learning_published_term WHERE term_key = 'max-drawdown'");

        JsonNode mention =
                data("/learning/articles/volatility-is-not-loss").at("/blocks/2/content/3");

        assertThat(mention.get("term").asText()).isEqualTo("max-drawdown");
        assertThat(mention.get("owned").asBoolean()).isFalse();
        assertThat(mention.get("aliases")).isEmpty();
    }

    @Test
    @DisplayName("CURRENT 판 용어의 도움말 문장은 그 글 용어 블록의 뜻 문장과 같고 모양은 term.json과 같다 (SC-003)")
    void currentTermSentenceIsMeaning() throws Exception {
        publishCurrentFixture();

        JsonNode term = data("/learning/terms/volatility");
        JsonNode termBlock = data("/learning/articles/volatility-is-not-loss").at("/blocks/3");

        assertThat(termBlock.get("type").asText()).isEqualTo("term");
        assertThat(term.get("sentence").asText()).isEqualTo(termBlock.get("meaning").asText());
        assertThat(term.get("articleId").asText()).isEqualTo("volatility-is-not-loss");
        assertThat(term.get("articleTitle").asText()).isEqualTo("변동성은 손실일까?");
        assertThat(fieldNames(term)).containsExactlyElementsOf(fieldNames(example("term.json")));
    }

    /** {@code actual}에서 {@code expected}의 최상위 키만 남긴다. 2판에서 더한 필드를 빼고 9월 판 필드만 비교할 때 쓴다. */
    private ObjectNode onlyKeysOf(JsonNode actual, JsonNode expected) {
        ObjectNode subset = objectMapper.createObjectNode();
        expected.fieldNames()
                .forEachRemaining(
                        name -> {
                            assertThat(actual.has(name)).as(name).isTrue();
                            subset.set(name, actual.get(name));
                        });
        return subset;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
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

    /** 글의 term 블록과 문장이 같은지는 게시 경로를 거치는 B02의 {@code LearningAdminApiIT}가 본다(SC-003). */
    @Test
    @DisplayName("게시된 용어 응답이 term.json과 같다")
    void termMatchesExample() throws Exception {
        seedFullSectorMap();
        publishListFixture();

        JsonNode term = data("/learning/terms/max-drawdown");

        assertThat(term).isEqualTo(example("term.json"));
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
