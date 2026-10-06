package com.ssafy.thorp.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thorp.learning.support.AdminCalls;
import com.ssafy.thorp.learning.support.LearningSeeds;
import com.ssafy.thorp.learning.support.LockWaits;
import com.ssafy.thorp.support.IntegrationTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 배우기 관리 API를 실제로 호출한다(learning-admin-api, quickstart 1 "덮어쓰기 방지"·3-2~3-8의 서버 쪽).
 *
 * <p><b>게시 전 검사 422의 {@code error}는 {@code
 * docs/specs/learning-api/examples/publish-check-failed.json}과 JSON 동치다.</b> 다른 응답은 예시 파일과 키 구성이
 * 같은지 본다. 값(시각·대응표 버전)은 실행마다 달라 동치로 비교할 수 없다.
 *
 * <p>공개 조회({@code LearningPublicApiIT})와 달리 판을 SQL로 심지 않는다. 게시 유스케이스를 거쳐 만든 판을 공개 조회가 보는지 확인하는 것이
 * 목적이다(SC-003·SC-006).
 *
 * <p><b>Spring 컨텍스트를 새로 만들지 않는다.</b> {@link IntegrationTestSupport}의 설정을 그대로 물려받아 캐시된 컨텍스트를 재사용한다.
 * 관리 계정 7은 {@code PostgresTestSupport}가 준다.
 */
class LearningAdminApiIT extends IntegrationTestSupport {

    /** gradle test의 작업 디렉터리는 backend/다. */
    private static final Path EXAMPLES = Path.of("..", "docs", "specs", "learning-api", "examples");

    private static final long ADMIN = 7;
    private static final String ARTICLE = "returns-and-losses";
    private static final String ADMIN_ARTICLE = "/admin/learning/articles/" + ARTICLE;

    private static final String GOOD_PARAGRAPH = "두 번째 문단이다.";
    private static final String BAD_PARAGRAPH = "이것이 추천 비중이다.";
    private static final String GOOD_SENTENCE = "고점에서 가장 크게 줄어든 비율이다.";
    private static final String BAD_SENTENCE = "고점 대비 -30% 줄어든 폭이다.";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    private LearningSeeds seeds;
    private AdminCalls calls;

    @BeforeEach
    void setUp() {
        seeds = new LearningSeeds(jdbcTemplate, objectMapper);
        calls = new AdminCalls(mockMvc, objectMapper, ADMIN);
        seeds.reset();
    }

    @AfterEach
    void tearDown() {
        seeds.reset();
    }

    // --- 호출 도우미 ---

    private JsonNode publicCall(String url, int expectedStatus) throws Exception {
        return calls.call(get(url), expectedStatus);
    }

    private JsonNode example(String file) throws Exception {
        return objectMapper.readTree(Files.readString(EXAMPLES.resolve(file)));
    }

    private MockHttpServletRequestBuilder saveDraft(JsonNode body) {
        return put(ADMIN_ARTICLE).contentType(MediaType.APPLICATION_JSON).content(body.toString());
    }

    private MockHttpServletRequestBuilder publish(int expectedDraftVersion) {
        return post(ADMIN_ARTICLE + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedDraftVersion\":" + expectedDraftVersion + "}");
    }

    private MockHttpServletRequestBuilder unpublish() {
        return post(ADMIN_ARTICLE + "/unpublish");
    }

    private JsonNode adminListItem(String articleId) throws Exception {
        for (JsonNode item :
                calls.adminData(get("/admin/learning/articles"), 200).get("articles")) {
            if (articleId.equals(item.get("articleId").asText())) {
                return item;
            }
        }
        throw new AssertionError("no admin list item for " + articleId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void seedFullSectorMap() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("SEMICONDUCTOR", "반도체");
        names.put("BATTERY", "2차전지");
        seeds.seedSectorMap(names);
    }

    /**
     * {@code returns-and-losses} 초안. 블록 2가 두 번째 문단이고 용어 둘이 {@code term} 블록으로 한 번씩 들어 있다. 숫자는 {@code
     * hypothetical} 블록에만 있다.
     */
    private ObjectNode draft(
            int expectedDraftVersion,
            String title,
            String secondParagraph,
            int batteryWeightTenths,
            String maxDrawdownLabel,
            String maxDrawdownSentence) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedDraftVersion", expectedDraftVersion);
        body.put("title", title);
        body.put("question", "가장 크게 줄었던 폭은 왜 시작 금액 대비 손실과 다른가?");
        body.put("readingOrder", 8);
        ArrayNode blocks = body.putArray("blocks");
        blocks.addObject().put("type", "paragraph").put("text", "가장 크게 줄었던 폭은 고점에서 잰다.");
        blocks.addObject().put("type", "term").put("termId", "max-drawdown");
        blocks.addObject().put("type", "paragraph").put("text", secondParagraph);
        blocks.addObject().put("type", "term").put("termId", "net-total-return");
        blocks.addObject()
                .put("type", "hypothetical")
                .put("text", "100만 원으로 시작해 120만 원까지 오른 뒤 90만 원으로 내려갔다면 고점 대비 25% 줄어든 것이다.");
        ArrayNode sectors = body.putObject("example").putArray("sectors");
        sectors.addObject().put("code", "SEMICONDUCTOR").put("weightTenths", 500);
        sectors.addObject().put("code", "BATTERY").put("weightTenths", batteryWeightTenths);
        body.putArray("sources")
                .addObject()
                .put("label", "합성 출처")
                .put("url", "https://example.com/source");
        ArrayNode terms = body.putArray("terms");
        terms.addObject()
                .put("termId", "max-drawdown")
                .put("label", maxDrawdownLabel)
                .put("sentence", maxDrawdownSentence);
        terms.addObject()
                .put("termId", "net-total-return")
                .put("label", "비용 차감 후 전체 수익률")
                .put("sentence", "거래비용을 뺀 뒤 전체 기간의 수익률이다.");
        return body;
    }

    private ObjectNode goodDraft(int expectedDraftVersion) {
        return draft(
                expectedDraftVersion, "수익과 손실", GOOD_PARAGRAPH, 500, "가장 크게 줄었던 폭", GOOD_SENTENCE);
    }

    /** quickstart 3-2: 비중 50.0%·45.0%, 문단에 '추천 비중', 용어 문장에 '-30%'. */
    private ObjectNode quickstartBadDraft(int expectedDraftVersion) {
        return draft(
                expectedDraftVersion, "수익과 손실", BAD_PARAGRAPH, 450, "가장 크게 줄었던 폭", BAD_SENTENCE);
    }

    /** 예시 파일과 키 구성이 같은지 본다. 객체는 키 집합을, 배열은 양쪽 첫 원소를 비교한다. 예시가 객체인데 실제 값이 null이면(없는 예시·기록) 건너뛴다. */
    private static void assertSameShape(JsonNode actual, JsonNode expected, String path) {
        if (expected.isObject()) {
            if (actual.isNull()) {
                return;
            }
            assertThat(actual.isObject()).as(path).isTrue();
            assertThat(fieldNames(actual))
                    .as(path)
                    .containsExactlyInAnyOrderElementsOf(fieldNames(expected));
            expected.fields()
                    .forEachRemaining(
                            field ->
                                    assertSameShape(
                                            actual.get(field.getKey()),
                                            field.getValue(),
                                            path + "." + field.getKey()));
        } else if (expected.isArray() && !expected.isEmpty() && !actual.isEmpty()) {
            assertSameShape(actual.get(0), expected.get(0), path + "[0]");
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> iterator = node.fieldNames();
        iterator.forEachRemaining(names::add);
        return names;
    }

    // --- §2 목록 ---

    @Test
    @DisplayName("목록은 초기 자리 27편이고 모두 DRAFT, 기록 없음이며 예시 파일과 키 구성이 같다")
    void listShowsTwentySevenDrafts() throws Exception {
        JsonNode data = calls.adminData(get("/admin/learning/articles"), 200);

        assertThat(data.get("articles")).hasSize(27);
        assertThat(data.get("articles"))
                .allSatisfy(
                        item -> {
                            assertThat(item.get("status").asText()).isEqualTo("DRAFT");
                            assertThat(item.get("draftVersion").asInt()).isZero();
                            assertThat(item.get("hasUnpublishedChanges").asBoolean()).isFalse();
                            assertThat(item.get("needsUpdate").isBoolean()).isTrue();
                            assertThat(item.get("needsUpdate").asBoolean()).isFalse();
                            assertThat(item.get("lastPublication").isNull()).isTrue();
                        });
        // 순서는 초안 단계 번호 → 단계 안 순서 → articleId다(learning-admin-api §2).
        assertThat(data.get("articles"))
                .extracting(
                        item ->
                                item.at("/stage/number").asInt()
                                        + "-"
                                        + item.get("stagePosition").asInt())
                .startsWith("1-1", "1-2", "1-3", "1-4", "2-1", "2-2", "2-3", "2-4", "3-1")
                .endsWith("5-3", "6-1", "6-2");
        assertThat(data.get("articles").get(0).get("articleId").asText())
                .isEqualTo("sectors-and-etf");
        assertThat(data.get("articles"))
                .allSatisfy(
                        item -> {
                            assertThat(item.at("/stage/released").asBoolean()).isFalse();
                            assertThat(item.get("publishedEdition").isNull()).isTrue();
                            assertThat(item.get("needsReview").isNull()).isTrue();
                            assertThat(item.get("pendingTransfers").asInt()).isZero();
                        });
        assertThat(data.get("articles"))
                .filteredOn(item -> "V1".equals(item.get("format").asText()))
                .hasSize(9);
        assertSameShape(data, example("admin-articles-list.json"), "$");
    }

    // --- §3 만들기 ---

    @Test
    @DisplayName("만들기는 201 빈 V2 초안이고, 같은 articleId는 409, 형식이 틀리면 400이다")
    void createArticle() throws Exception {
        String body =
                "{\"articleId\":\"equal-weight-basics\",\"title\":\"똑같이 나누면\","
                        + "\"stageKey\":\"etf-market-basics\",\"stagePosition\":5}";

        JsonNode created =
                calls.adminData(
                        post("/admin/learning/articles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        201);

        assertThat(created.get("articleId").asText()).isEqualTo("equal-weight-basics");
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.get("draftVersion").asInt()).isZero();
        assertThat(created.at("/draft/example").isNull()).isTrue();
        assertThat(created.at("/draft/blocks")).isEmpty();
        assertThat(created.get("publishedRevisionNo").isNull()).isTrue();
        assertThat(created.at("/draft/format").asText()).isEqualTo("V2");
        assertThat(created.at("/draft/stageKey").asText()).isEqualTo("etf-market-basics");
        assertThat(created.at("/draft/stagePosition").asInt()).isEqualTo(5);
        // 읽는 순서 값은 단계 번호 × 100 + 단계 안 순서다(learning-admin-api §3).
        assertThat(created.at("/draft/readingOrder").asInt()).isEqualTo(105);
        assertSameShape(created, example("admin-article-v2.json"), "$");

        JsonNode taken =
                calls.adminError(
                        post("/admin/learning/articles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        409);
        assertThat(taken.get("code").asText()).isEqualTo("LEARNING_ARTICLE_ID_TAKEN");

        JsonNode invalid =
                calls.adminError(
                        post("/admin/learning/articles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"articleId\":\"Bad_Key\",\"title\":\"t\","
                                                + "\"stageKey\":\"etf-market-basics\",\"stagePosition\":5}"),
                        400);
        assertThat(invalid.get("code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(invalid.at("/details/fields"))
                .extracting(field -> field.get("field").asText())
                .contains("articleId");
    }

    // --- §4~§7 없는 글 ---

    @Test
    @DisplayName("없는 글은 읽기·저장·게시·게시 취소 모두 404 LEARNING_ARTICLE_NOT_FOUND다")
    void missingArticleIsNotFound() throws Exception {
        String missing = "/admin/learning/articles/no-such-article";

        assertThat(calls.adminError(get(missing), 404).get("code").asText())
                .isEqualTo("LEARNING_ARTICLE_NOT_FOUND");
        assertThat(
                        calls.adminError(
                                        put(missing)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(goodDraft(0).toString()),
                                        404)
                                .get("code")
                                .asText())
                .isEqualTo("LEARNING_ARTICLE_NOT_FOUND");
        assertThat(
                        calls.adminError(
                                        post(missing + "/publish")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"expectedDraftVersion\":0}"),
                                        404)
                                .get("code")
                                .asText())
                .isEqualTo("LEARNING_ARTICLE_NOT_FOUND");
        assertThat(calls.adminError(post(missing + "/unpublish"), 404).get("code").asText())
                .isEqualTo("LEARNING_ARTICLE_NOT_FOUND");
        assertThat(
                        calls.adminError(get("/admin/learning/articles/Bad_Key"), 404)
                                .get("code")
                                .asText())
                .isEqualTo("LEARNING_ARTICLE_NOT_FOUND");
    }

    // --- §5 저장 ---

    @Test
    @DisplayName(
            "저장은 draftVersion을 1 올리고, 옛 버전은 409 LEARNING_DRAFT_CHANGED와 currentDraftVersion이다 (quickstart 1)")
    void saveDraftBumpsVersionAndRejectsStaleVersion() throws Exception {
        JsonNode saved = calls.adminData(saveDraft(goodDraft(0)), 200);

        assertThat(saved.get("draftVersion").asInt()).isEqualTo(1);
        assertThat(saved.at("/draft/blocks/2/text").asText()).isEqualTo(GOOD_PARAGRAPH);
        assertThat(saved.at("/draft/blocks/1"))
                .isEqualTo(
                        objectMapper.readTree("{\"type\":\"term\",\"termId\":\"max-drawdown\"}"));
        assertThat(saved.at("/draft/sources/0/url").asText())
                .isEqualTo("https://example.com/source");
        assertSameShape(saved, example("admin-article.json"), "$");
        assertThat(calls.adminData(get(ADMIN_ARTICLE), 200)).isEqualTo(saved);

        JsonNode stale = calls.adminError(saveDraft(goodDraft(0)), 409);

        assertThat(stale.get("code").asText()).isEqualTo("LEARNING_DRAFT_CHANGED");
        assertThat(stale.at("/details/currentDraftVersion").asInt()).isEqualTo(1);
        assertThat(calls.adminData(get(ADMIN_ARTICLE), 200).get("draftVersion").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("다른 글의 termId는 409 LEARNING_TERM_ID_TAKEN과 termId·ownerArticleId다")
    void foreignTermIdIsTaken() throws Exception {
        ObjectNode body = goodDraft(0);
        ((ArrayNode) body.get("terms"))
                .addObject()
                .put("termId", "rebalancing")
                .put("label", "리밸런싱")
                .put("sentence", "비중을 되돌리는 일이다.");

        JsonNode error = calls.adminError(saveDraft(body), 409);

        assertThat(error.get("code").asText()).isEqualTo("LEARNING_TERM_ID_TAKEN");
        assertThat(error.at("/details/termId").asText()).isEqualTo("rebalancing");
        assertThat(error.at("/details/ownerArticleId").asText())
                .isEqualTo("weights-and-rebalancing");
    }

    @Test
    @DisplayName("알 수 없는 블록 type은 조용히 버리지 않고 400 봉투로 거절한다")
    void unknownBlockTypeIsRejected() throws Exception {
        ObjectNode body = goodDraft(0);
        ((ArrayNode) body.get("blocks")).addObject().put("type", "video").put("text", "x");

        JsonNode error = calls.adminError(saveDraft(body), 400);

        assertThat(error.get("code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(error.at("/details/fields"))
                .extracting(field -> field.get("field").asText())
                .containsExactly("blocks[5]");
        assertThat(calls.adminData(get(ADMIN_ARTICLE), 200).get("draftVersion").asInt()).isZero();
    }

    @Test
    @DisplayName("한 요청 안의 중복 termId는 400이고 위치가 terms다")
    void duplicateTermIdInRequestIsRejected() throws Exception {
        ObjectNode body = goodDraft(0);
        ((ArrayNode) body.get("terms"))
                .addObject()
                .put("termId", "max-drawdown")
                .put("label", "또 하나")
                .put("sentence", "겹친 용어다.");

        JsonNode error = calls.adminError(saveDraft(body), 400);

        assertThat(error.at("/details/fields"))
                .extracting(field -> field.get("field").asText())
                .containsExactly("terms");
    }

    @Test
    @DisplayName("옛 버전에 다른 글의 termId까지 실으면 LEARNING_DRAFT_CHANGED가 먼저다")
    void staleVersionIsReportedBeforeTakenTermId() throws Exception {
        calls.adminData(saveDraft(goodDraft(0)), 200);
        ObjectNode body = goodDraft(0);
        ((ArrayNode) body.get("terms"))
                .addObject()
                .put("termId", "rebalancing")
                .put("label", "리밸런싱")
                .put("sentence", "비중을 되돌리는 일이다.");

        assertThat(calls.adminError(saveDraft(body), 409).get("code").asText())
                .isEqualTo("LEARNING_DRAFT_CHANGED");
    }

    /**
     * 글 A가 초안에서 뺐지만 아직 공개 중인 용어를 글 B가 가져가지 못한다. 막지 않으면 B의 게시가 A의 게시 색인 행을 덮어써 A 본문의 도움말이 B의 문장을
     * 가리키고, B를 게시 취소하면 A가 공개 중인데도 도움말이 사라진다(SC-003).
     */
    @Test
    @DisplayName("다른 글이 게시 중인 termId는 초안에서 빠졌어도 409 LEARNING_TERM_ID_TAKEN이다")
    void publishedTermIdCannotBeTakenByAnotherArticle() throws Exception {
        seedFullSectorMap();
        calls.adminData(saveDraft(goodDraft(0)), 200);
        calls.adminData(publish(1), 200);
        ObjectNode withoutMaxDrawdown = goodDraft(1);
        ((ArrayNode) withoutMaxDrawdown.get("terms")).remove(0);
        calls.adminData(saveDraft(withoutMaxDrawdown), 200);

        ObjectNode other = goodDraft(0);
        ((ArrayNode) other.get("terms")).remove(1);
        JsonNode error =
                calls.adminError(
                        put("/admin/learning/articles/backtest")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(other.toString()),
                        409);

        assertThat(error.get("code").asText()).isEqualTo("LEARNING_TERM_ID_TAKEN");
        assertThat(error.at("/details/termId").asText()).isEqualTo("max-drawdown");
        assertThat(error.at("/details/ownerArticleId").asText()).isEqualTo(ARTICLE);
        JsonNode term = publicCall("/learning/terms/max-drawdown", 200).get("data");
        assertThat(term.get("articleId").asText()).isEqualTo(ARTICLE);
        assertThat(term.get("sentence").asText()).isEqualTo(GOOD_SENTENCE);
    }

    @Test
    @DisplayName("저장할 수 없는 값은 400이고 details.fields에 위치가 실린다")
    void invalidFieldsAreListed() throws Exception {
        ObjectNode body = goodDraft(0);
        ((ObjectNode) body.at("/example/sectors/1")).put("weightTenths", 1001);
        ((ObjectNode) body.at("/sources/0")).put("url", "ftp://example.com");

        JsonNode error = calls.adminError(saveDraft(body), 400);

        assertThat(error.at("/details/fields"))
                .extracting(field -> field.get("field").asText())
                .contains("example.sectors[1].weightTenths", "sources[0].url");
    }

    @Test
    @DisplayName("요청에서 뺀 용어는 초안에서 빠지고 다른 글이 그 termId를 쓸 수 있게 된다")
    void removedTermLeavesDraft() throws Exception {
        ObjectNode body = goodDraft(0);
        ((ArrayNode) body.get("terms")).remove(1);

        JsonNode saved = calls.adminData(saveDraft(body), 200);

        assertThat(saved.at("/draft/terms"))
                .extracting(term -> term.get("termId").asText())
                .containsExactly("max-drawdown");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM learning_term WHERE term_key = 'net-total-return'",
                                Integer.class))
                .isZero();
    }

    // --- §6·§7 게시 흐름 ---

    @Test
    @DisplayName(
            "검사에 걸린 게시는 422이고 error가 publish-check-failed.json과 같으며 아무것도 바뀌지 않는다 (quickstart 3-2)")
    void failingPublishChangesNothing() throws Exception {
        seedFullSectorMap();
        calls.adminData(saveDraft(quickstartBadDraft(0)), 200);

        JsonNode error = calls.adminError(publish(1), 422);

        assertThat(error).isEqualTo(example("publish-check-failed.json"));
        assertThat(count("learning_article_revision")).isZero();
        assertThat(count("learning_publication_event")).isZero();
        assertThat(count("learning_published_term")).isZero();
        assertThat(publicCall("/learning/articles/" + ARTICLE, 404).at("/error/code").asText())
                .isEqualTo("LEARNING_ARTICLE_UNAVAILABLE");
        assertThat(adminListItem(ARTICLE).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("대응표가 없으면 게시는 EXAMPLE_SECTOR_IN_MAP으로 거절된다 (quickstart 3-4)")
    void publishWithoutSectorMapIsRejected() throws Exception {
        calls.adminData(saveDraft(goodDraft(0)), 200);

        JsonNode error = calls.adminError(publish(1), 422);

        assertThat(error.at("/details/failures"))
                .extracting(failure -> failure.get("check").asText())
                .containsExactly("EXAMPLE_SECTOR_IN_MAP");
        assertThat(count("learning_article_revision")).isZero();
    }

    @Test
    @DisplayName("옛 expectedDraftVersion으로는 게시하지 않는다")
    void publishWithStaleVersionIsRejected() throws Exception {
        seedFullSectorMap();
        calls.adminData(saveDraft(goodDraft(0)), 200);

        JsonNode error = calls.adminError(publish(0), 409);

        assertThat(error.get("code").asText()).isEqualTo("LEARNING_DRAFT_CHANGED");
        assertThat(error.at("/details/currentDraftVersion").asInt()).isEqualTo(1);
        assertThat(count("learning_article_revision")).isZero();
    }

    /**
     * 게시 → 게시 뒤 저장 → 대응표 변화 → 다시 게시 → 게시 취소 → 다시 게시를 한 흐름으로 따라간다. 판 번호가 게시 취소 뒤에도 이어서 오르는지까지 보려면 앞
     * 단계의 상태가 필요하다.
     */
    @Test
    @DisplayName("게시·게시 뒤 저장·재게시·게시 취소가 공개 조회에 함께 반영된다 (quickstart 3-3·3-5·3-8, SC-003·SC-006)")
    void publishLifecycle() throws Exception {
        seedFullSectorMap();

        // 3-3 통과하는 게시
        calls.adminData(saveDraft(goodDraft(0)), 200);
        JsonNode published = calls.adminData(publish(1), 200);

        assertSameShape(published, example("publish-success.json"), "$");
        // V1 초안·공개 전 단계 → 이전 판 고치기(LEGACY), v1 검사(research R16).
        assertThat(published.get("edition").asText()).isEqualTo("LEGACY");
        assertThat(published.get("checkVersion").asText()).isEqualTo("learning-publish-checks-v1");
        assertThat(published.get("renamedQuestions")).isEmpty();
        assertThat(published.get("transferredTerms")).isEmpty();
        assertThat(published.get("articleId").asText()).isEqualTo(ARTICLE);
        assertThat(published.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.get("revisionNo").asInt()).isEqualTo(1);
        assertThat(published.get("checkVersion").asText()).isEqualTo("learning-publish-checks-v1");
        assertThat(published.get("sectorMapVersion").asText()).isEqualTo(LearningSeeds.MAP_VERSION);

        JsonNode publicList = publicCall("/learning/articles", 200).at("/data/articles");
        assertThat(publicList)
                .extracting(item -> item.get("articleId").asText())
                .containsExactly(ARTICLE);
        JsonNode article = publicCall("/learning/articles/" + ARTICLE, 200).get("data");
        JsonNode term = publicCall("/learning/terms/max-drawdown", 200).get("data");
        assertThat(article.at("/blocks/1/sentence").asText()).isEqualTo(GOOD_SENTENCE);
        assertThat(term.get("sentence").asText()).isEqualTo(GOOD_SENTENCE);
        assertThat(article.get("publishedAt").asText())
                .isEqualTo(published.get("publishedAt").asText());
        assertThat(article.at("/example/available").asBoolean()).isTrue();

        JsonNode listed = adminListItem(ARTICLE);
        assertThat(listed.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(listed.get("hasUnpublishedChanges").asBoolean()).isFalse();
        assertThat(listed.get("needsUpdate").isBoolean()).isTrue();
        assertThat(listed.get("needsUpdate").asBoolean()).isFalse();
        assertThat(listed.at("/lastPublication/action").asText()).isEqualTo("PUBLISH");
        assertThat(listed.at("/lastPublication/revisionNo").asInt()).isEqualTo(1);
        assertThat(listed.at("/lastPublication/actorUserId").asLong()).isEqualTo(ADMIN);
        assertSameShape(listed, example("admin-articles-list.json").at("/articles/1"), "$");
        assertThat(calls.adminData(get(ADMIN_ARTICLE), 200).get("publishedRevisionNo").asInt())
                .isEqualTo(1);

        // 3-5 게시된 글을 저장만 한다
        calls.adminData(
                saveDraft(draft(1, "수익과 손실", "저장만 한 문단이다.", 500, "가장 크게 줄었던 폭", "저장만 한 문장이다.")),
                200);

        assertThat(publicCall("/learning/articles/" + ARTICLE, 200).get("data")).isEqualTo(article);
        assertThat(publicCall("/learning/terms/max-drawdown", 200).get("data")).isEqualTo(term);
        assertThat(adminListItem(ARTICLE).get("hasUnpublishedChanges").asBoolean()).isTrue();

        // 대응표에서 예시 코드가 빠지면 갱신 필요, 대응표가 없으면 판정할 수 없다
        seeds.clearSectorMap();
        seeds.seedSectorMap(Map.of("SEMICONDUCTOR", "반도체"));
        assertThat(adminListItem(ARTICLE).get("needsUpdate").asBoolean()).isTrue();
        seeds.clearSectorMap();
        assertThat(adminListItem(ARTICLE).get("needsUpdate").isNull()).isTrue();
        seedFullSectorMap();

        // 제목·용어 이름·문장을 바꿔 다시 게시한다(US3 시나리오 7, quickstart 5-3의 서버 쪽)
        calls.adminData(
                saveDraft(
                        draft(2, "수익과 손실 다시 보기", GOOD_PARAGRAPH, 500, "고점 대비 하락 폭", "바꾼 용어 문장이다.")),
                200);
        assertThat(calls.adminData(publish(3), 200).get("revisionNo").asInt()).isEqualTo(2);

        JsonNode republished = publicCall("/learning/articles/" + ARTICLE, 200).get("data");
        JsonNode retermed = publicCall("/learning/terms/max-drawdown", 200).get("data");
        assertThat(republished.get("title").asText()).isEqualTo("수익과 손실 다시 보기");
        assertThat(republished.at("/blocks/1/label").asText()).isEqualTo("고점 대비 하락 폭");
        assertThat(republished.at("/blocks/1/sentence").asText()).isEqualTo("바꾼 용어 문장이다.");
        assertThat(retermed.get("label").asText()).isEqualTo("고점 대비 하락 폭");
        assertThat(retermed.get("sentence").asText()).isEqualTo("바꾼 용어 문장이다.");
        assertThat(retermed.get("articleTitle").asText()).isEqualTo("수익과 손실 다시 보기");

        // 3-8 게시 취소
        int eventsBeforeUnpublish = count("learning_publication_event");
        JsonNode unpublished = calls.adminData(unpublish(), 200);

        assertThat(unpublished)
                .isEqualTo(
                        objectMapper.readTree(
                                "{\"articleId\":\"returns-and-losses\",\"status\":\"DRAFT\",\"unpublishedRevisionNo\":2}"));
        assertSameShape(unpublished, example("unpublish.json"), "$");
        assertThat(count("learning_publication_event")).isEqualTo(eventsBeforeUnpublish + 1);
        assertThat(publicCall("/learning/articles/" + ARTICLE, 404).at("/error/code").asText())
                .isEqualTo("LEARNING_ARTICLE_UNAVAILABLE");
        assertThat(publicCall("/learning/terms/max-drawdown", 404).at("/error/code").asText())
                .isEqualTo("LEARNING_TERM_NOT_READY");
        assertThat(publicCall("/learning/articles", 200).at("/data/articles")).isEmpty();
        JsonNode afterUnpublish = adminListItem(ARTICLE);
        assertThat(afterUnpublish.get("status").asText()).isEqualTo("DRAFT");
        assertThat(afterUnpublish.at("/lastPublication/action").asText()).isEqualTo("UNPUBLISH");
        assertThat(afterUnpublish.at("/lastPublication/revisionNo").asInt()).isEqualTo(2);
        assertThat(afterUnpublish.get("hasUnpublishedChanges").asBoolean()).isFalse();
        assertThat(calls.adminData(get(ADMIN_ARTICLE), 200).get("publishedRevisionNo").isNull())
                .isTrue();

        // 한 번 더 취소하면 200 null이고 기록이 늘지 않는다
        JsonNode again = calls.adminData(unpublish(), 200);
        assertThat(again.get("unpublishedRevisionNo").isNull()).isTrue();
        assertThat(count("learning_publication_event")).isEqualTo(eventsBeforeUnpublish + 1);

        // 다시 게시하면 판 번호가 이어서 오른다
        assertThat(calls.adminData(publish(3), 200).get("revisionNo").asInt()).isEqualTo(3);
        assertThat(publicCall("/learning/terms/max-drawdown", 200).at("/data/sentence").asText())
                .isEqualTo("바꾼 용어 문장이다.");
    }

    // --- 동시 요청이 UNIQUE 제약에 부딪힐 때 (T082) ---

    @Test
    @DisplayName(
            "글마다 게시는 초안 단계 행을 먼저 잠근다 — 그 단계의 공개가 도는 동안 기다렸다가 공개된 단계를 보고 V1 글을 409 LEARNING_FORMAT_OUTDATED로 거절한다")
    void publishWaitsForStageReleaseInProgress() throws Exception {
        seedFullSectorMap();
        calls.adminData(saveDraft(goodDraft(0)), 200);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        JsonNode error;
        try (Connection holder = LockWaits.rawConnection(dataSource);
                Connection watcher = LockWaits.rawConnection(dataSource)) {
            holder.setAutoCommit(false);
            // 단계 공개가 단계 행을 잠그고 공개일을 쓴 채 아직 커밋하지 않은 상태를 흉내 낸다.
            try (PreparedStatement release =
                    holder.prepareStatement(
                            "UPDATE learning_stage SET released_at = now(), released_by_user_id = 7"
                                    + " WHERE stage_key = 'returns-and-risk'")) {
                release.executeUpdate();
            }
            Future<JsonNode> publishing = executor.submit(() -> calls.adminError(publish(1), 409));
            LockWaits.awaitBlockedBy(holder, watcher);
            holder.commit();
            error = publishing.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(error.get("code").asText()).isEqualTo("LEARNING_FORMAT_OUTDATED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM learning_article_revision", Integer.class))
                .as("공개된 단계에 LEGACY 판을 남기지 않는다")
                .isZero();
    }

    @Test
    @DisplayName("같은 articleId를 동시에 만들면 늦은 쪽은 500이 아니라 409 LEARNING_ARTICLE_ID_TAKEN이다")
    void concurrentCreateWithSameArticleIdIsConflict() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = LockWaits.rawConnection(dataSource);
                Connection watcher = LockWaits.rawConnection(dataSource)) {
            holder.setAutoCommit(false);
            holder.createStatement()
                    .executeUpdate(
                            "INSERT INTO learning_article (article_key, title, question, reading_order,"
                                    + " status, format, stage_id, stage_position, created_at, updated_at)"
                                    + " SELECT 'race-article', '먼저 만든 글', '', 105, 'DRAFT', 'V2', id, 5,"
                                    + " now(), now() FROM learning_stage WHERE stage_key = 'etf-market-basics'");
            Future<JsonNode> late =
                    executor.submit(
                            () ->
                                    calls.admin(
                                            post("/admin/learning/articles")
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content(
                                                            "{\"articleId\":\"race-article\",\"title\":\"늦은 글\","
                                                                    + "\"stageKey\":\"etf-market-basics\",\"stagePosition\":5}"),
                                            409));

            LockWaits.awaitBlockedBy(holder, watcher);
            holder.commit();

            assertThat(late.get(20, TimeUnit.SECONDS).at("/error/code").asText())
                    .isEqualTo("LEARNING_ARTICLE_ID_TAKEN");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 새 termId를 두 글이 동시에 저장하면 늦은 쪽은 409 LEARNING_TERM_ID_TAKEN과 소유 글이다")
    void concurrentSaveWithSameTermIdIsConflict() throws Exception {
        ObjectNode body = goodDraft(0);
        ((ArrayNode) body.get("terms"))
                .addObject()
                .put("termId", "race-term")
                .put("label", "경쟁 용어")
                .put("sentence", "동시에 넣은 용어다.");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = LockWaits.rawConnection(dataSource);
                Connection watcher = LockWaits.rawConnection(dataSource)) {
            holder.setAutoCommit(false);
            holder.createStatement()
                    .executeUpdate(
                            "INSERT INTO learning_term (term_key, article_id, label, sentence,"
                                    + " created_at, updated_at)"
                                    + " SELECT 'race-term', id, '먼저 넣은 용어', '', now(), now()"
                                    + " FROM learning_article WHERE article_key = 'backtest'");
            Future<JsonNode> late = executor.submit(() -> calls.admin(saveDraft(body), 409));

            LockWaits.awaitBlockedBy(holder, watcher);
            holder.commit();

            JsonNode error = late.get(20, TimeUnit.SECONDS).get("error");
            assertThat(error.get("code").asText()).isEqualTo("LEARNING_TERM_ID_TAKEN");
            assertThat(error.at("/details/termId").asText()).isEqualTo("race-term");
            assertThat(error.at("/details/ownerArticleId").asText()).isEqualTo("backtest");
        } finally {
            executor.shutdownNow();
        }
        assertThat(calls.adminData(get(ADMIN_ARTICLE), 200).get("draftVersion").asInt()).isZero();
    }
}
