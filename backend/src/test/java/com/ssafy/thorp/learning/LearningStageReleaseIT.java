package com.ssafy.thorp.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thorp.learning.support.LearningSeeds;
import com.ssafy.thorp.learning.support.V2Draft;
import com.ssafy.thorp.support.IntegrationTestSupport;
import com.ssafy.thorp.support.Requesters;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
 * 단계 목록·미리 검사·단계 공개(learning-admin-api §9~§11, data-model §10-3, quickstart 8-1~8-4·8-6·8-7, tasks
 * T165·T166).
 *
 * <p>{@code admin-stages.json}·{@code stage-readiness.json}·{@code stage-check-failed.json}은 JSON
 * 동치, {@code stage-release.json}은 {@code releasedAt} 밖의 키를 JSON 동치로 대조한다({@code
 * docs/specs/learning-api/README.md}).
 *
 * <p>앞 단계는 {@link LearningSeeds#markStageReleased}로 공개해 두고, 공개할 단계의 글은 관리 API 저장으로 채운다. 비로그인 401·권한
 * 없음 404·CSRF 403은 관리 경로 전체를 함께 보는 {@code LearningAdminAccessIT}에 단계 경로 셋을 더해 확인한다.
 */
class LearningStageReleaseIT extends IntegrationTestSupport {

    private static final Path EXAMPLES = Path.of("..", "docs", "specs", "learning-api", "examples");

    private static final long ADMIN = 7;
    private static final String STAGE_2 = "returns-and-risk";
    private static final List<String> STAGE_2_ARTICLES =
            List.of(
                    "returns-and-losses",
                    "same-return-different-feel",
                    "recovery-period",
                    "volatility-is-not-loss");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

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

    // --- 호출 도우미 ---

    private JsonNode call(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        String body =
                mockMvc.perform(request)
                        .andExpect(status().is(expectedStatus))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    /** 상태를 기대하지 않고 응답 본문만 읽는다. 동시 요청처럼 어느 쪽이 성공할지 정해지지 않을 때 쓴다. */
    private JsonNode callAny(MockHttpServletRequestBuilder request) throws Exception {
        return objectMapper.readTree(
                mockMvc.perform(request)
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode admin(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        return call(request.with(Requesters.as(ADMIN)).with(csrf()), expectedStatus);
    }

    private JsonNode adminData(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        JsonNode body = admin(request, expectedStatus);
        assertThat(body.get("success").asBoolean()).isTrue();
        return body.get("data");
    }

    private JsonNode adminError(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        JsonNode body = admin(request, expectedStatus);
        assertThat(body.get("success").asBoolean()).isFalse();
        return body.get("error");
    }

    private JsonNode example(String file) throws Exception {
        return objectMapper.readTree(Files.readString(EXAMPLES.resolve(file)));
    }

    private static MockHttpServletRequestBuilder save(String articleId, JsonNode body) {
        return put("/admin/learning/articles/" + articleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString());
    }

    private static MockHttpServletRequestBuilder readiness(String stageKey) {
        return get("/admin/learning/stages/" + stageKey + "/readiness");
    }

    private MockHttpServletRequestBuilder release(String stageKey, Map<String, Integer> versions) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode articles = body.putArray("articles");
        versions.forEach(
                (articleId, version) ->
                        articles.addObject()
                                .put("articleId", articleId)
                                .put("expectedDraftVersion", version));
        return post("/admin/learning/stages/" + stageKey + "/release")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString());
    }

    /** 미리 검사가 돌려준 글과 초안 번호. 화면이 단계 공개 요청에 그대로 싣는 값이다. */
    private Map<String, Integer> versionsFrom(JsonNode readiness) {
        Map<String, Integer> versions = new LinkedHashMap<>();
        readiness
                .get("articles")
                .forEach(
                        article ->
                                versions.put(
                                        article.get("articleId").asText(),
                                        article.get("draftVersion").asInt()));
        return versions;
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private Timestamp releasedAt(String stageKey) {
        return jdbcTemplate.queryForObject(
                "SELECT released_at FROM learning_stage WHERE stage_key = ?",
                Timestamp.class,
                stageKey);
    }

    private int draftVersion(String articleId) {
        return jdbcTemplate.queryForObject(
                "SELECT draft_version FROM learning_article WHERE article_key = ?",
                Integer.class,
                articleId);
    }

    private void setDraftVersion(String articleId, int version) {
        jdbcTemplate.update(
                "UPDATE learning_article SET draft_version = ? WHERE article_key = ?",
                version,
                articleId);
    }

    /** 공개에 따라 바뀌는 것 전부. 걸리거나 미리 검사만 했을 때 이 값이 그대로여야 한다. */
    private List<Object> publicationState() {
        List<Object> state = new ArrayList<>();
        for (String table :
                List.of(
                        "learning_article_revision",
                        "learning_publication_event",
                        "learning_published_term",
                        "learning_release_log")) {
            state.add(table + "=" + count(table));
        }
        state.add(
                jdbcTemplate.queryForList(
                        "SELECT article_key, status, draft_version, published_stage_id,"
                                + " first_released_at FROM learning_article ORDER BY article_key"));
        state.add(
                jdbcTemplate.queryForList(
                        "SELECT stage_key, released_at, released_by_user_id FROM learning_stage"
                                + " ORDER BY number"));
        state.add(
                jdbcTemplate.queryForList(
                        "SELECT term_key, article_id, transfer_from_article_id FROM learning_term"
                                + " ORDER BY term_key, article_id"));
        return state;
    }

    // --- 준비 ---

    private V2Draft draft(String title, int position) {
        return V2Draft.of(objectMapper, title, STAGE_2, position);
    }

    /**
     * 1단계가 공개되고 2단계의 네 글이 V2 초안인 상태(quickstart 8-4). {@code returns-and-losses}는 LEGACY 판이 세 번
     * 게시됐고, {@code volatility-is-not-loss}는 {@code inverse-volatility}의 용어 {@code volatility}를
     * 넘겨받는다.
     *
     * @param recovery {@code recovery-period}의 초안. 검사에 걸리게 하려면 바꿔 넘긴다
     */
    private void prepareStageTwo(V2Draft recovery) throws Exception {
        seeds.markStageReleased("etf-market-basics");
        for (int revision = 0; revision < 3; revision++) {
            seeds.publishMinimal("returns-and-losses", "수익과 손실", "합성 질문", 8);
        }
        adminData(
                save(
                        "returns-and-losses",
                        draft("수익과 손실", 1)
                                .term("net-total-return", "비용 차감 후 전체 수익률")
                                .term("max-drawdown", "가장 크게 줄었던 폭")
                                .build(0)),
                200);
        adminData(
                save(
                        "same-return-different-feel",
                        draft("같은 수익률인데 왜 느낌이 다를까?", 2).term("path-dependence", "지나온 길").build(0)),
                200);
        adminData(save("recovery-period", recovery.build(0)), 200);
        adminData(
                save(
                        "volatility-is-not-loss",
                        draft("변동성은 손실일까?", 4)
                                .transferredTerm("volatility", "변동성", "inverse-volatility")
                                .build(0)),
                200);
    }

    private V2Draft passingRecovery() {
        return draft("떨어진 뒤 얼마나 걸려 돌아왔을까?", 3).term("recovery-period", "회복 기간");
    }

    // --- §9 단계 목록 ---

    @Test
    @DisplayName("단계 목록은 admin-stages.json과 같다 — 단계마다 초안 배치의 글과 공개 배치만 남은 글, 1단계만 공개할 수 있다")
    void listMatchesExample() throws Exception {
        seeds.publishMinimal("sectors-and-etf", "섹터와 ETF", "합성 질문", 1);
        jdbcTemplate.update(
                "UPDATE learning_article SET format = 'V2', draft_version = 9"
                        + " WHERE article_key = 'sectors-and-etf'");
        setDraftVersion("etf-tracking-difference", 3);
        // 4단계에 게시한 뒤 초안만 5단계 4번으로 옮긴 글.
        seeds.publishMinimal("backtest", "백테스트", "합성 질문", 6);
        jdbcTemplate.update(
                "UPDATE learning_article SET stage_id = (SELECT id FROM learning_stage"
                        + " WHERE stage_key = 'pitfalls-and-validation'), stage_position = 4"
                        + " WHERE article_key = 'backtest'");

        assertThat(adminData(get("/admin/learning/stages"), 200))
                .isEqualTo(example("admin-stages.json"));
    }

    // --- §10 미리 검사 ---

    @Test
    @DisplayName("미리 검사는 stage-readiness.json과 같고 아무것도 쓰지 않는다 (quickstart 8-2)")
    void readinessMatchesExampleAndWritesNothing() throws Exception {
        prepareStageTwo(passingRecovery().keySummaryLines(2));
        setDraftVersion("returns-and-losses", 9);
        setDraftVersion("same-return-different-feel", 5);
        setDraftVersion("recovery-period", 4);
        setDraftVersion("volatility-is-not-loss", 7);
        List<Object> before = publicationState();
        JsonNode adminListBefore = adminData(get("/admin/learning/articles"), 200);

        JsonNode readiness = adminData(readiness(STAGE_2), 200);

        assertThat(readiness).isEqualTo(example("stage-readiness.json"));
        assertThat(publicationState()).isEqualTo(before);
        assertThat(adminData(get("/admin/learning/articles"), 200)).isEqualTo(adminListBefore);
        assertThat(call(get("/learning/curriculum"), 200).at("/data/changeLog")).isEmpty();
    }

    @Test
    @DisplayName("미리 검사는 앞 단계 공개 전을 ORDER로, V1 초안을 FORMAT으로, 초안을 옮긴 글을 PLACEMENT로 싣는다")
    void readinessListsBlockersAndArticleItems() throws Exception {
        seeds.publishMinimal("backtest", "백테스트", "합성 질문", 6);
        jdbcTemplate.update(
                "UPDATE learning_article SET stage_id = (SELECT id FROM learning_stage"
                        + " WHERE stage_key = 'pitfalls-and-validation'), stage_position = 4"
                        + " WHERE article_key = 'backtest'");

        JsonNode readiness = adminData(readiness("backtest-and-costs"), 200);

        assertThat(readiness.get("ready").asBoolean()).isFalse();
        assertThat(readiness.get("blockers")).extracting(JsonNode::asText).containsExactly("ORDER");
        assertThat(readiness.get("articles"))
                .extracting(article -> article.get("articleId").asText())
                .as("초안 배치의 글, 그다음 공개 배치만 남은 글")
                .containsExactly(
                        "same-conditions",
                        "transaction-cost",
                        "turnover",
                        "short-history",
                        "backtest");
        JsonNode moved = readiness.at("/articles/4/failures");
        assertThat(moved)
                .extracting(failure -> failure.get("check").asText())
                .containsExactly("FORMAT", "PLACEMENT");
        assertThat(moved.get(0).get("location"))
                .isEqualTo(objectMapper.readTree("{\"part\":\"article\"}"));
        assertThat(readiness.at("/articles/1/failures"))
                .extracting(failure -> failure.get("check").asText())
                .containsExactly("FORMAT");
        // V2 빈 초안은 v2 검사 항목을 싣는다.
        assertThat(readiness.at("/articles/0/failures"))
                .extracting(failure -> failure.get("check").asText())
                .contains("REQUIRED_FIELDS", "KEY_SUMMARY", "QUIZ");

        assertThat(adminError(readiness("no-such-stage"), 404).get("code").asText())
                .isEqualTo("LEARNING_STAGE_NOT_FOUND");
    }

    // --- §11 단계 공개 ---

    @Test
    @DisplayName(
            "1단계를 공개하지 않은 채 2단계를 공개하면 409 LEARNING_STAGE_ORDER이고 아무것도 바뀌지 않는다 (quickstart 8-1)")
    void releasingOutOfOrderIsConflict() throws Exception {
        List<Object> before = publicationState();

        JsonNode error =
                adminError(
                        release(STAGE_2, Map.of("returns-and-losses", 0, "recovery-period", 0)),
                        409);

        assertThat(error.get("code").asText()).isEqualTo("LEARNING_STAGE_ORDER");
        assertThat(publicationState()).isEqualTo(before);
    }

    @Test
    @DisplayName("글이 없는 단계는 409 LEARNING_STAGE_EMPTY, 없는 단계는 404다")
    void emptyAndMissingStage() throws Exception {
        for (String stageKey :
                List.of(
                        "etf-market-basics",
                        "returns-and-risk",
                        "allocation-rules",
                        "backtest-and-costs",
                        "pitfalls-and-validation")) {
            seeds.markStageReleased(stageKey);
        }
        jdbcTemplate.update(
                "UPDATE learning_article SET stage_id = (SELECT id FROM learning_stage"
                        + " WHERE stage_key = 'pitfalls-and-validation'), stage_position = 9"
                        + " WHERE article_key IN ('market-flow-fields', 'similar-periods')");

        assertThat(adminData(readiness("economy-and-regimes"), 200).get("blockers"))
                .extracting(JsonNode::asText)
                .containsExactly("EMPTY");
        assertThat(adminError(release("economy-and-regimes", Map.of()), 409).get("code").asText())
                .isEqualTo("LEARNING_STAGE_EMPTY");
        assertThat(adminError(release("no-such-stage", Map.of()), 404).get("code").asText())
                .isEqualTo("LEARNING_STAGE_NOT_FOUND");
    }

    @Test
    @DisplayName("요청의 글 목록이나 초안 번호가 공개 집합과 다르면 409 LEARNING_STAGE_CHANGED와 서버의 목록이다")
    void changedListOrVersionIsConflict() throws Exception {
        prepareStageTwo(passingRecovery());
        Map<String, Integer> versions = versionsFrom(adminData(readiness(STAGE_2), 200));
        List<Object> before = publicationState();

        Map<String, Integer> missing = new LinkedHashMap<>(versions);
        missing.remove("recovery-period");
        Map<String, Integer> stale = new LinkedHashMap<>(versions);
        stale.put("recovery-period", 0);
        Map<String, Integer> extra = new LinkedHashMap<>(versions);
        extra.put("etf-fees", 0);

        for (Map<String, Integer> request : List.of(missing, stale, extra)) {
            JsonNode error = adminError(release(STAGE_2, request), 409);
            assertThat(error.get("code").asText()).isEqualTo("LEARNING_STAGE_CHANGED");
            assertThat(error.at("/details/articles"))
                    .extracting(
                            article ->
                                    article.get("articleId").asText()
                                            + "@"
                                            + article.get("draftVersion").asInt())
                    .containsExactly(
                            "returns-and-losses@1",
                            "same-return-different-feel@1",
                            "recovery-period@1",
                            "volatility-is-not-loss@1");
        }
        assertThat(publicationState()).isEqualTo(before);
    }

    @Test
    @DisplayName(
            "글 하나라도 걸리면 stage-check-failed.json(422)이고 판·기록·색인·공개일·변경 기록·초안이 그대로다 (quickstart 8-3)")
    void oneFailingArticleBlocksTheWholeStage() throws Exception {
        prepareStageTwo(passingRecovery().firstQuestionChoices(3).secondQuestionBothCorrect());
        Map<String, Integer> versions = versionsFrom(adminData(readiness(STAGE_2), 200));
        List<Object> before = publicationState();
        JsonNode inverseVolatility =
                adminData(get("/admin/learning/articles/inverse-volatility"), 200);

        JsonNode error = adminError(release(STAGE_2, versions), 422);

        assertThat(error).isEqualTo(example("stage-check-failed.json"));
        assertThat(publicationState()).isEqualTo(before);
        assertThat(adminData(get("/admin/learning/articles/inverse-volatility"), 200))
                .as("넘기기도 일어나지 않았다")
                .isEqualTo(inverseVolatility);
        assertThat(call(get("/learning/curriculum"), 200).at("/data/releasedStageCount").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "단계 공개는 stage-release.json과 같고 모든 쓰기가 같은 시각이며, 다시 쓴 글 수와 제목·넘기기를 남긴다 (quickstart 8-4)")
    void releaseMatchesExample() throws Exception {
        prepareStageTwo(passingRecovery());
        int inverseVolatilityVersion = draftVersion("inverse-volatility");
        Map<String, Integer> versions = versionsFrom(adminData(readiness(STAGE_2), 200));
        assertThat(adminData(readiness(STAGE_2), 200).get("ready").asBoolean()).isTrue();

        JsonNode released = adminData(release(STAGE_2, versions), 200);

        ObjectNode withoutTime = released.deepCopy();
        withoutTime.remove("releasedAt");
        ObjectNode expected = (ObjectNode) example("stage-release.json");
        expected.remove("releasedAt");
        assertThat(withoutTime).isEqualTo(expected);

        // 공개일은 이 요청의 시각 하나다. 판·게시 기록·첫 공개 시각·변경 기록이 모두 같은 값이다.
        Instant releasedAt = Instant.parse(released.get("releasedAt").asText());
        assertThat(releasedAt(STAGE_2).toInstant()).isEqualTo(releasedAt);
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT DISTINCT published_at FROM learning_article_revision"
                                        + " WHERE edition = 'CURRENT'",
                                Timestamp.class))
                .extracting(Timestamp::toInstant)
                .containsExactly(releasedAt);
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT DISTINCT e.occurred_at FROM learning_publication_event e"
                                        + " JOIN learning_article_revision r ON r.id = e.revision_id"
                                        + " WHERE r.edition = 'CURRENT'",
                                Timestamp.class))
                .extracting(Timestamp::toInstant)
                .containsExactly(releasedAt);
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT DISTINCT first_released_at FROM learning_article"
                                        + " WHERE first_released_at IS NOT NULL",
                                Timestamp.class))
                .extracting(Timestamp::toInstant)
                .containsExactly(releasedAt);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT released_by_user_id FROM learning_stage WHERE stage_key = ?",
                                Long.class,
                                STAGE_2))
                .isEqualTo(ADMIN);

        // 변경 기록: 새 글 셋, 다시 쓴 글 하나(LEGACY 판이 있던 returns-and-losses)와 그때 제목.
        Map<String, Object> log =
                jdbcTemplate.queryForMap(
                        "SELECT entry_key, kind, CAST(new_count AS INTEGER) AS new_count,"
                                + " CAST(rewritten_count AS INTEGER) AS rewritten_count,"
                                + " rewritten_titles::text AS titles, actor_user_id, occurred_at"
                                + " FROM learning_release_log");
        assertThat(log)
                .containsEntry("entry_key", "stage:returns-and-risk")
                .containsEntry("kind", "STAGE_RELEASED")
                .containsEntry("new_count", 3)
                .containsEntry("rewritten_count", 1)
                .containsEntry("titles", "[\"수익과 손실\"]")
                .containsEntry("actor_user_id", ADMIN);
        assertThat(((Timestamp) log.get("occurred_at")).toInstant()).isEqualTo(releasedAt);

        // 넘기기: 옛 글의 초안에서 용어가 빠지고 초안 번호가 1 오른다. 새 글 초안의 transferFrom은 비고 초안 번호는 그대로다.
        assertThat(draftVersion("inverse-volatility")).isEqualTo(inverseVolatilityVersion + 1);
        assertThat(
                        adminData(get("/admin/learning/articles/inverse-volatility"), 200)
                                .at("/draft/terms"))
                .extracting(term -> term.get("termId").asText())
                .containsExactly("inverse-volatility");
        JsonNode newOwner = adminData(get("/admin/learning/articles/volatility-is-not-loss"), 200);
        assertThat(newOwner.get("draftVersion").asInt()).isEqualTo(1);
        assertThat(newOwner.at("/draft/terms/0/transferFrom").isNull()).isTrue();
        assertThat(newOwner.get("publishedEdition").asText()).isEqualTo("CURRENT");

        // 공개 조회: 2단계가 OPEN이고 번호가 2-1…2-4다.
        JsonNode curriculum = call(get("/learning/curriculum"), 200).get("data");
        assertThat(curriculum.at("/stages/1/status").asText()).isEqualTo("OPEN");
        assertThat(curriculum.at("/stages/1/articles"))
                .extracting(article -> article.get("number").asText())
                .containsExactly("2-1", "2-2", "2-3", "2-4");
        assertThat(curriculum.at("/stages/1/releasedAt").asText()).isEqualTo(releasedAt.toString());
        assertThat(curriculum.get("changeLog")).hasSize(1);
        assertThat(
                        call(get("/learning/articles/returns-and-losses"), 200)
                                .at("/data/edition")
                                .asText())
                .isEqualTo("CURRENT");
        assertThat(call(get("/learning/terms/volatility"), 200).at("/data/articleId").asText())
                .isEqualTo("volatility-is-not-loss");

        // 되돌리는 경로는 없다. 다시 공개하면 409다.
        assertThat(adminError(release(STAGE_2, versions), 409).get("code").asText())
                .isEqualTo("LEARNING_STAGE_ALREADY_RELEASED");
    }

    @Test
    @DisplayName("공개된 단계에서 옮겨 온 글은 함께 게시되지만 새 글에도 다시 쓴 글에도 세지 않는다 (research R27)")
    void articleMovedFromReleasedStageIsNotCounted() throws Exception {
        prepareStageTwo(passingRecovery());
        // 1단계에 공개된 적 있는 글을 2단계 5번으로 옮겨 다시 쓴다.
        seeds.publishCurrentMinimal("etf-fees", "ETF 보수는 어디로 빠져나갈까?", Map.of());
        jdbcTemplate.update(
                "UPDATE learning_article SET first_released_at = TIMESTAMPTZ '2026-11-12 10:00:00+09'"
                        + " WHERE article_key = 'etf-fees'");
        adminData(
                save(
                        "etf-fees",
                        draft("ETF 보수는 어디로 빠져나갈까?", 5).term("total-expense", "총보수").build(0)),
                200);
        Map<String, Integer> versions = versionsFrom(adminData(readiness(STAGE_2), 200));
        assertThat(versions).containsKey("etf-fees");

        JsonNode released = adminData(release(STAGE_2, versions), 200);

        assertThat(released.get("published"))
                .extracting(article -> article.get("articleId").asText())
                .containsExactly(
                        "returns-and-losses",
                        "same-return-different-feel",
                        "recovery-period",
                        "volatility-is-not-loss",
                        "etf-fees");
        assertThat(
                        jdbcTemplate.queryForMap(
                                "SELECT CAST(new_count AS INTEGER) AS new_count,"
                                        + " CAST(rewritten_count AS INTEGER) AS rewritten_count"
                                        + " FROM learning_release_log"))
                .containsEntry("new_count", 3)
                .containsEntry("rewritten_count", 1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT first_released_at FROM learning_article WHERE article_key = 'etf-fees'",
                                Timestamp.class))
                .as("첫 공개 시각은 한 번 쓴 뒤 바꾸지 않는다")
                .isEqualTo(Timestamp.from(Instant.parse("2026-11-12T01:00:00Z")));
    }

    @Test
    @DisplayName(
            "공개된 단계에 새 글을 글마다 게시하면 ARTICLE_ADDED 한 줄이고, 게시 취소 뒤 다시 게시해도 늘지 않는다 (quickstart 8-6)")
    void addingArticleToReleasedStageLogsOnce() throws Exception {
        prepareStageTwo(passingRecovery());
        adminData(release(STAGE_2, versionsFrom(adminData(readiness(STAGE_2), 200))), 200);
        adminData(
                post("/admin/learning/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"articleId\":\"drawdown-depth\",\"title\":\"하락 폭은 어떻게 읽을까?\","
                                        + "\"stageKey\":\"returns-and-risk\",\"stagePosition\":5}"),
                201);
        adminData(
                save(
                        "drawdown-depth",
                        draft("하락 폭은 어떻게 읽을까?", 5).term("drawdown-depth", "하락 폭").build(0)),
                200);

        JsonNode published =
                adminData(
                        post("/admin/learning/articles/drawdown-depth/publish")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedDraftVersion\":1}"),
                        200);
        assertThat(published.get("edition").asText()).isEqualTo("CURRENT");

        JsonNode curriculum = call(get("/learning/curriculum"), 200).get("data");
        assertThat(curriculum.at("/stages/1/articles/4/number").asText()).isEqualTo("2-5");
        assertThat(curriculum.at("/changeLog/0/entryKey").asText())
                .isEqualTo("article:drawdown-depth");
        assertThat(curriculum.at("/changeLog/0/articleTitle").asText()).isEqualTo("하락 폭은 어떻게 읽을까?");
        assertThat(curriculum.at("/notices/0/entryKey").asText())
                .isEqualTo("article:drawdown-depth");

        adminData(post("/admin/learning/articles/drawdown-depth/unpublish"), 200);
        adminData(
                post("/admin/learning/articles/drawdown-depth/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedDraftVersion\":1}"),
                200);
        assertThat(count("learning_release_log")).isEqualTo(2);
    }

    @Test
    @DisplayName("공개된 단계의 글을 모두 게시 취소하면 REPREPARING이고 다시 게시해도 공개일·변경 기록이 그대로다 (quickstart 8-7)")
    void unpublishingEverythingKeepsRelease() throws Exception {
        prepareStageTwo(passingRecovery());
        Instant releasedAt =
                Instant.parse(
                        adminData(
                                        release(
                                                STAGE_2,
                                                versionsFrom(adminData(readiness(STAGE_2), 200))),
                                        200)
                                .get("releasedAt")
                                .asText());
        for (String articleId : STAGE_2_ARTICLES) {
            adminData(post("/admin/learning/articles/" + articleId + "/unpublish"), 200);
        }

        JsonNode reprepared = call(get("/learning/curriculum"), 200).get("data");
        assertThat(reprepared.at("/stages/1/status").asText()).isEqualTo("REPREPARING");
        assertThat(reprepared.get("releasedStageCount").asInt()).isEqualTo(2);

        adminData(
                post("/admin/learning/articles/recovery-period/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"expectedDraftVersion\":"
                                        + draftVersion("recovery-period")
                                        + "}"),
                200);
        JsonNode reopened = call(get("/learning/curriculum"), 200).get("data");
        assertThat(reopened.at("/stages/1/status").asText()).isEqualTo("OPEN");
        assertThat(reopened.at("/stages/1/releasedAt").asText()).isEqualTo(releasedAt.toString());
        assertThat(reopened.get("changeLog")).hasSize(1);
        assertThat(releasedAt(STAGE_2).toInstant()).isEqualTo(releasedAt);
    }

    // --- 동시성 (T166) ---

    /** 테스트 연결 풀과 별개인 연결. 커밋하지 않은 잠금으로 요청을 세워 두는 데 쓴다. */
    private Connection rawConnection() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        return DriverManager.getConnection(
                hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
    }

    /**
     * {@code holder} 뒤에 줄 선 세션이 {@code sessions}개가 될 때까지 기다린다. DB 전체의 잠금 대기를 세면 다른 세션의 대기 때문에 곧바로
     * 넘어간다({@code LearningAdminApiIT}와 같은 이유). 같은 행을 기다리는 둘째 요청은 holder가 아니라 첫째 요청의 행 잠금을 기다리므로,
     * holder에서 시작하는 대기 사슬 전체를 센다.
     */
    private static void awaitBlockedBy(Connection holder, Connection watcher, int sessions)
            throws Exception {
        int holderPid;
        try (ResultSet pid = holder.createStatement().executeQuery("SELECT pg_backend_pid()")) {
            pid.next();
            holderPid = pid.getInt(1);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            try (PreparedStatement blocked =
                    watcher.prepareStatement(
                            """
                            WITH RECURSIVE chain (pid) AS (
                                SELECT pid FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))
                                UNION
                                SELECT a.pid FROM pg_stat_activity a JOIN chain c
                                  ON c.pid = ANY (pg_blocking_pids(a.pid))
                            )
                            SELECT count(*) FROM chain
                            """)) {
                blocked.setInt(1, holderPid);
                try (ResultSet rows = blocked.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) >= sessions) {
                        return;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("requests never waited on the lock held by pid " + holderPid);
    }

    @Test
    @DisplayName("같은 단계 공개 둘이 동시에 오면 하나만 성공하고 다른 하나는 409 LEARNING_STAGE_ALREADY_RELEASED다")
    void concurrentReleasesSucceedOnce() throws Exception {
        prepareStageTwo(passingRecovery());
        Map<String, Integer> versions = versionsFrom(adminData(readiness(STAGE_2), 200));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holder = rawConnection();
                Connection watcher = rawConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement()
                    .executeQuery(
                            "SELECT id FROM learning_stage WHERE stage_key = 'returns-and-risk' FOR UPDATE")
                    .close();
            List<Future<JsonNode>> requests = new ArrayList<>();
            for (int request = 0; request < 2; request++) {
                requests.add(
                        executor.submit(
                                () ->
                                        callAny(
                                                release(STAGE_2, versions)
                                                        .with(Requesters.as(ADMIN))
                                                        .with(csrf()))));
            }
            awaitBlockedBy(holder, watcher, 2);
            holder.rollback();

            List<String> outcomes = new ArrayList<>();
            for (Future<JsonNode> request : requests) {
                JsonNode body = request.get(30, TimeUnit.SECONDS);
                outcomes.add(
                        body.get("success").asBoolean() ? "OK" : body.at("/error/code").asText());
            }
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "LEARNING_STAGE_ALREADY_RELEASED");
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("learning_release_log")).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM learning_article_revision WHERE edition = 'CURRENT'",
                                Integer.class))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("공개가 글 잠금을 기다리는 사이 그 글의 저장이 커밋되면 409 LEARNING_STAGE_CHANGED이고 아무것도 쓰지 않는다")
    void saveCommittedWhileReleaseWaitsIsConflict() throws Exception {
        prepareStageTwo(passingRecovery());
        Map<String, Integer> versions = versionsFrom(adminData(readiness(STAGE_2), 200));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = rawConnection();
                Connection watcher = rawConnection()) {
            holder.setAutoCommit(false);
            // 저장과 같은 효과: 글 행을 잠그고 초안 번호를 올린 채 커밋하지 않는다.
            holder.createStatement()
                    .executeUpdate(
                            "UPDATE learning_article SET draft_version = draft_version + 1"
                                    + " WHERE article_key = 'recovery-period'");
            Future<JsonNode> late = executor.submit(() -> admin(release(STAGE_2, versions), 409));
            awaitBlockedBy(holder, watcher, 1);
            holder.commit();

            JsonNode error = late.get(30, TimeUnit.SECONDS).get("error");
            assertThat(error.get("code").asText()).isEqualTo("LEARNING_STAGE_CHANGED");
            assertThat(error.at("/details/articles/2/draftVersion").asInt()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
        assertThat(releasedAt(STAGE_2)).isNull();
        assertThat(count("learning_release_log")).isZero();
    }

    @Test
    @DisplayName("공개가 먼저 끝나면 공개 전에 읽은 초안의 저장은 LEARNING_DRAFT_CHANGED 없이 저장된다 — 공개는 초안 번호를 바꾸지 않는다")
    void saveAfterReleaseKeepsDraftVersion() throws Exception {
        prepareStageTwo(passingRecovery());
        adminData(release(STAGE_2, versionsFrom(adminData(readiness(STAGE_2), 200))), 200);

        JsonNode saved = adminData(save("recovery-period", passingRecovery().build(1)), 200);

        assertThat(saved.get("draftVersion").asInt()).isEqualTo(2);
    }
}
