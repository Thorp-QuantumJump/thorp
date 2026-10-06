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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * 용어 넘겨받기(data-model §7-2, research R24, learning-admin-api §5·§6·§11, quickstart 8-5, tasks
 * T147·T166).
 *
 * <p>기존 용어 {@code volatility}는 9월 판 글 {@code inverse-volatility}가 소유하고, 2단계 새 글 {@code
 * volatility-is-not-loss}가 넘겨받는다. 저장할 때와 게시할 때 모두 {@code transferFrom}이 지금 소유 글과 같은지 확인하고, 넘기기는
 * 넘겨받는 글이 게시될 때 한 트랜잭션에서 한다.
 */
class LearningTermTransferIT extends IntegrationTestSupport {

    private static final long ADMIN = 7;
    private static final String NEW_OWNER = "volatility-is-not-loss";
    private static final String OLD_OWNER = "inverse-volatility";
    private static final String STAGE_2 = "returns-and-risk";

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

    private JsonNode admin(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        return call(request.with(Requesters.as(ADMIN)).with(csrf()), expectedStatus);
    }

    private JsonNode adminData(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        return admin(request, expectedStatus).get("data");
    }

    private JsonNode adminError(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        return admin(request, expectedStatus).get("error");
    }

    private static MockHttpServletRequestBuilder save(String articleId, JsonNode body) {
        return put("/admin/learning/articles/" + articleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString());
    }

    private static MockHttpServletRequestBuilder publish(String articleId, int draftVersion) {
        return post("/admin/learning/articles/" + articleId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedDraftVersion\":" + draftVersion + "}");
    }

    private JsonNode draftOf(String articleId) throws Exception {
        return adminData(get("/admin/learning/articles/" + articleId), 200);
    }

    private JsonNode adminListItem(String articleId) throws Exception {
        for (JsonNode item : adminData(get("/admin/learning/articles"), 200).get("articles")) {
            if (item.get("articleId").asText().equals(articleId)) {
                return item;
            }
        }
        throw new AssertionError("not in admin list: " + articleId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private V2Draft newOwnerDraft(String transferFrom) {
        V2Draft draft = V2Draft.of(objectMapper, "변동성은 손실일까?", STAGE_2, 4);
        return transferFrom == null
                ? draft.term("volatility", "변동성")
                : draft.transferredTerm("volatility", "변동성", transferFrom);
    }

    /**
     * {@code inverse-volatility}의 V1 초안. 본문은 두 용어 블록이고, {@code withVolatility}가 거짓이면 {@code
     * volatility} 용어를 뺀다.
     */
    private ObjectNode oldOwnerV1Draft(int expectedDraftVersion, boolean withVolatility) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedDraftVersion", expectedDraftVersion);
        body.put("title", "변동성에 따라 나누기");
        body.put("question", "덜 흔들린 섹터에 더 많이 담는다는 것은 무슨 뜻일까?");
        body.put("readingOrder", 3);
        ArrayNode blocks = body.putArray("blocks");
        blocks.addObject().put("type", "paragraph").put("text", "합성 문단");
        blocks.addObject().put("type", "term").put("termId", "inverse-volatility");
        if (withVolatility) {
            blocks.addObject().put("type", "term").put("termId", "volatility");
        }
        body.putNull("example");
        body.putArray("sources");
        ArrayNode terms = body.putArray("terms");
        terms.addObject()
                .put("termId", "inverse-volatility")
                .put("label", "변동성에 따라 나누기")
                .put("sentence", "덜 흔들린 쪽에 더 담는 규칙이다.");
        if (withVolatility) {
            terms.addObject()
                    .put("termId", "volatility")
                    .put("label", "변동성")
                    .put("sentence", "옛 문장");
        }
        return body;
    }

    private Map<String, Integer> readinessVersions(String stageKey) throws Exception {
        Map<String, Integer> versions = new LinkedHashMap<>();
        adminData(get("/admin/learning/stages/" + stageKey + "/readiness"), 200)
                .get("articles")
                .forEach(
                        article ->
                                versions.put(
                                        article.get("articleId").asText(),
                                        article.get("draftVersion").asInt()));
        return versions;
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

    /** 1단계가 공개되고 2단계의 네 글이 검사를 통과하는 V2 초안이다. 넘겨받는 글의 {@code transferFrom}은 옛 소유 글이다. */
    private void prepareStageTwo() throws Exception {
        seeds.markStageReleased("etf-market-basics");
        adminData(
                save(
                        "returns-and-losses",
                        V2Draft.of(objectMapper, "수익과 손실", STAGE_2, 1)
                                .term("net-total-return", "비용 차감 후 전체 수익률")
                                .term("max-drawdown", "가장 크게 줄었던 폭")
                                .build(0)),
                200);
        adminData(
                save(
                        "same-return-different-feel",
                        V2Draft.of(objectMapper, "같은 수익률인데 왜 느낌이 다를까?", STAGE_2, 2)
                                .term("path-dependence", "지나온 길")
                                .build(0)),
                200);
        adminData(
                save(
                        "recovery-period",
                        V2Draft.of(objectMapper, "떨어진 뒤 얼마나 걸려 돌아왔을까?", STAGE_2, 3)
                                .term("recovery-period", "회복 기간")
                                .build(0)),
                200);
        adminData(save(NEW_OWNER, newOwnerDraft(OLD_OWNER).build(0)), 200);
    }

    // --- 저장 (§5) ---

    @Test
    @DisplayName(
            "transferFrom이 지금 소유 글이 아니면 409 LEARNING_TERM_TRANSFER_INVALID와 소유 글이고 초안이 바뀌지 않는다")
    void transferFromAnotherArticleIsInvalid() throws Exception {
        JsonNode error =
                adminError(save(NEW_OWNER, newOwnerDraft("recovery-period").build(0)), 409);

        assertThat(error.get("code").asText()).isEqualTo("LEARNING_TERM_TRANSFER_INVALID");
        assertThat(error.at("/details/termId").asText()).isEqualTo("volatility");
        assertThat(error.at("/details/ownerArticleId").asText()).isEqualTo(OLD_OWNER);
        assertThat(draftOf(NEW_OWNER).get("draftVersion").asInt()).isZero();
    }

    @Test
    @DisplayName("넘겨받기 없이 다른 글의 용어를 쓰면 지금처럼 409 LEARNING_TERM_ID_TAKEN이다")
    void usingTermWithoutTransferIsTaken() throws Exception {
        JsonNode error = adminError(save(NEW_OWNER, newOwnerDraft(null).build(0)), 409);

        assertThat(error.get("code").asText()).isEqualTo("LEARNING_TERM_ID_TAKEN");
        assertThat(error.at("/details/ownerArticleId").asText()).isEqualTo(OLD_OWNER);
    }

    @Test
    @DisplayName(
            "넘겨받기 저장은 초안에 transferFrom을 남기고 관리 목록의 pendingTransfers가 1이며, 같은 용어를 다른 글이 또 넘겨받으면 409다")
    void secondIncomingTransferIsInvalid() throws Exception {
        JsonNode saved = adminData(save(NEW_OWNER, newOwnerDraft(OLD_OWNER).build(0)), 200);

        assertThat(saved.at("/draft/terms/0/transferFrom").asText()).isEqualTo(OLD_OWNER);
        assertThat(adminListItem(NEW_OWNER).get("pendingTransfers").asInt()).isEqualTo(1);
        assertThat(adminListItem(OLD_OWNER).get("pendingTransfers").asInt()).isZero();

        JsonNode error =
                adminError(
                        save(
                                "recovery-period",
                                V2Draft.of(objectMapper, "떨어진 뒤 얼마나 걸려 돌아왔을까?", STAGE_2, 3)
                                        .transferredTerm("volatility", "변동성", OLD_OWNER)
                                        .build(0)),
                        409);
        assertThat(error.get("code").asText()).isEqualTo("LEARNING_TERM_TRANSFER_INVALID");
        assertThat(draftOf("recovery-period").get("draftVersion").asInt()).isZero();
    }

    @Test
    @DisplayName("V1 초안의 용어에는 transferFrom을 둘 수 없다 (400, data-model §7-1)")
    void v1TermCannotTransfer() throws Exception {
        ObjectNode body = oldOwnerV1Draft(0, false);
        body.put("title", "수익과 손실");
        ((ObjectNode) body.at("/terms/0"))
                .put("termId", "sector")
                .put("transferFrom", "sectors-and-etf");

        JsonNode error = adminError(save("returns-and-losses", body), 400);

        assertThat(error.at("/details/fields"))
                .extracting(field -> field.get("field").asText())
                .contains("terms[0].transferFrom");
    }

    // --- 게시 (§6·§11) ---

    @Test
    @DisplayName(
            "공개된 단계의 글마다 게시가 넘기기를 한다 — 옛 글 색인·초안 행 삭제와 초안 번호 +1, 새 글 transferFrom 비움, 도움말은 새 글")
    void publishTransfersTerm() throws Exception {
        seeds.markStageReleased(STAGE_2);
        adminData(save(NEW_OWNER, newOwnerDraft(OLD_OWNER).build(0)), 200);

        JsonNode published = adminData(publish(NEW_OWNER, 1), 200);

        assertThat(published.get("transferredTerms"))
                .isEqualTo(
                        objectMapper.readTree(
                                "[{\"termId\":\"volatility\",\"fromArticleId\":\"inverse-volatility\","
                                        + "\"toArticleId\":\"volatility-is-not-loss\"}]"));
        JsonNode newOwner = draftOf(NEW_OWNER);
        assertThat(newOwner.get("draftVersion").asInt()).as("게시는 초안 번호를 올리지 않는다").isEqualTo(1);
        assertThat(newOwner.at("/draft/terms/0/transferFrom").isNull()).isTrue();
        JsonNode oldOwner = draftOf(OLD_OWNER);
        assertThat(oldOwner.get("draftVersion").asInt()).isEqualTo(1);
        assertThat(oldOwner.at("/draft/terms"))
                .extracting(term -> term.get("termId").asText())
                .containsExactly("inverse-volatility");
        JsonNode help = call(get("/learning/terms/volatility"), 200).get("data");
        assertThat(help.get("articleId").asText()).isEqualTo(NEW_OWNER);
        assertThat(help.get("sentence").asText()).isEqualTo("변동성의 뜻 문장이다.");
        assertThat(adminListItem(NEW_OWNER).get("pendingTransfers").asInt()).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT entry_key FROM learning_release_log", String.class))
                .as("공개된 단계에 처음 게시된 글이다")
                .isEqualTo("article:" + NEW_OWNER);
    }

    @Test
    @DisplayName("저장 뒤 옛 소유 글이 그 용어를 빼고 게시하지 않았으면 글마다 게시는 409이고 판·색인·두 글의 초안이 그대로다")
    void ownerVanishedBeforePublishIsInvalid() throws Exception {
        seeds.markStageReleased(STAGE_2);
        adminData(save(NEW_OWNER, newOwnerDraft(OLD_OWNER).build(0)), 200);
        adminData(save(OLD_OWNER, oldOwnerV1Draft(0, false)), 200);
        JsonNode newOwnerBefore = draftOf(NEW_OWNER);
        JsonNode oldOwnerBefore = draftOf(OLD_OWNER);

        JsonNode error = adminError(publish(NEW_OWNER, 1), 409);

        assertThat(error.get("code").asText()).isEqualTo("LEARNING_TERM_TRANSFER_INVALID");
        assertThat(error.at("/details/termId").asText()).isEqualTo("volatility");
        assertThat(error.at("/details/ownerArticleId").isMissingNode()).as("넘겨줄 소유 글이 없다").isTrue();
        assertThat(count("learning_article_revision")).isZero();
        assertThat(count("learning_published_term")).isZero();
        assertThat(count("learning_release_log")).isZero();
        assertThat(draftOf(NEW_OWNER)).isEqualTo(newOwnerBefore);
        assertThat(draftOf(OLD_OWNER)).isEqualTo(oldOwnerBefore);
    }

    @Test
    @DisplayName("같은 경우의 단계 공개는 422의 TERM_TRANSFER 항목이고 아무것도 쓰지 않는다")
    void ownerVanishedBeforeStageReleaseIsCheckFailure() throws Exception {
        prepareStageTwo();
        adminData(save(OLD_OWNER, oldOwnerV1Draft(0, false)), 200);

        JsonNode error = adminError(release(STAGE_2, readinessVersions(STAGE_2)), 422);

        assertThat(error.get("code").asText()).isEqualTo("LEARNING_STAGE_CHECK_FAILED");
        assertThat(error.at("/details/articles"))
                .extracting(article -> article.get("articleId").asText())
                .containsExactly(NEW_OWNER);
        assertThat(error.at("/details/articles/0/failures"))
                .extracting(
                        failure ->
                                failure.get("check").asText()
                                        + " "
                                        + failure.get("location").toString())
                .containsExactly("TERM_TRANSFER {\"part\":\"term\",\"termId\":\"volatility\"}");
        assertThat(count("learning_article_revision")).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT released_at FROM learning_stage WHERE stage_key = ?",
                                Object.class,
                                STAGE_2))
                .isNull();
    }

    @Test
    @DisplayName(
            "도움말은 단계 공개 전에는 옛 글·옛 문장, 공개 뒤에는 새 글·뜻 문장이고, 옛 글의 다음 이전 판 고치기 게시는 TERM_BLOCKS_MATCH로 걸린다 (quickstart 8-5)")
    void stageReleaseMovesHelpToNewArticle() throws Exception {
        seeds.publishLegacyWithTerm(OLD_OWNER, "변동성에 따라 나누기", "volatility", "변동성", "옛 문장");
        adminData(save(OLD_OWNER, oldOwnerV1Draft(0, true)), 200);
        prepareStageTwo();

        JsonNode before = call(get("/learning/terms/volatility"), 200).get("data");
        assertThat(before.get("articleId").asText()).isEqualTo(OLD_OWNER);
        assertThat(before.get("sentence").asText()).isEqualTo("옛 문장");
        // 공개 전에 읽어 둔 넘겨받는 글의 초안(transferFrom이 남아 있다).
        ObjectNode readBeforeRelease = newOwnerDraft(OLD_OWNER).build(1);

        adminData(release(STAGE_2, readinessVersions(STAGE_2)), 200);

        JsonNode after = call(get("/learning/terms/volatility"), 200).get("data");
        assertThat(after.get("articleId").asText()).isEqualTo(NEW_OWNER);
        assertThat(after.get("sentence").asText()).isEqualTo("변동성의 뜻 문장이다.");
        JsonNode oldOwner = adminListItem(OLD_OWNER);
        assertThat(oldOwner.get("draftVersion").asInt()).isEqualTo(2);
        assertThat(oldOwner.get("hasUnpublishedChanges").asBoolean()).isTrue();

        // 옛 글 초안에 남은 volatility 용어 블록은 다음 이전 판 고치기 게시에서 걸린다.
        JsonNode failed = adminError(publish(OLD_OWNER, 2), 422);
        assertThat(failed.at("/details/failures"))
                .extracting(failure -> failure.get("check").asText())
                .contains("TERM_BLOCKS_MATCH");

        // T166: 공개가 transferFrom을 비웠으므로 공개 전에 읽은 초안의 저장은 409이고 초안이 그대로다.
        JsonNode stale = adminError(save(NEW_OWNER, readBeforeRelease), 409);
        assertThat(stale.get("code").asText()).isEqualTo("LEARNING_TERM_TRANSFER_INVALID");
        assertThat(draftOf(NEW_OWNER).get("draftVersion").asInt()).isEqualTo(1);
        // 공개 뒤 다시 읽은 초안(transferFrom 없음)은 저장된다.
        assertThat(
                        adminData(save(NEW_OWNER, newOwnerDraft(null).build(1)), 200)
                                .get("draftVersion")
                                .asInt())
                .isEqualTo(2);
    }

    // --- 동시 저장 ---

    private Connection rawConnection() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        return DriverManager.getConnection(
                hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
    }

    /** 요청이 {@code holder}의 커밋하지 않은 행에 막혀 기다릴 때까지 기다린다({@code LearningAdminApiIT}와 같다). */
    private static void awaitBlockedBy(Connection holder, Connection watcher) throws Exception {
        int holderPid;
        try (ResultSet pid = holder.createStatement().executeQuery("SELECT pg_backend_pid()")) {
            pid.next();
            holderPid = pid.getInt(1);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            try (PreparedStatement blocked =
                    watcher.prepareStatement(
                            "SELECT count(*) FROM pg_stat_activity"
                                    + " WHERE ? = ANY (pg_blocking_pids(pid))")) {
                blocked.setInt(1, holderPid);
                try (ResultSet rows = blocked.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) > 0) {
                        return;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("request never waited on the row held by pid " + holderPid);
    }

    @Test
    @DisplayName("같은 용어를 두 글이 동시에 넘겨받으면 늦은 쪽은 500이 아니라 409 LEARNING_TERM_TRANSFER_INVALID다")
    void concurrentIncomingTransferIsInvalid() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = rawConnection();
                Connection watcher = rawConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement()
                    .executeUpdate(
                            "INSERT INTO learning_term (term_key, article_id, label, sentence,"
                                    + " transfer_from_article_id, created_at, updated_at)"
                                    + " SELECT 'volatility', r.id, '변동성', '', o.id, now(), now()"
                                    + " FROM learning_article r, learning_article o"
                                    + " WHERE r.article_key = 'recovery-period'"
                                    + " AND o.article_key = 'inverse-volatility'");
            Future<JsonNode> late =
                    executor.submit(
                            () -> admin(save(NEW_OWNER, newOwnerDraft(OLD_OWNER).build(0)), 409));

            awaitBlockedBy(holder, watcher);
            holder.commit();

            JsonNode error = late.get(20, TimeUnit.SECONDS).get("error");
            assertThat(error.get("code").asText()).isEqualTo("LEARNING_TERM_TRANSFER_INVALID");
            assertThat(error.at("/details/termId").asText()).isEqualTo("volatility");
        } finally {
            executor.shutdownNow();
        }
        assertThat(draftOf(NEW_OWNER).get("draftVersion").asInt()).isZero();
        List<String> incoming = new ArrayList<>();
        jdbcTemplate
                .queryForList(
                        "SELECT a.article_key FROM learning_term t JOIN learning_article a"
                                + " ON a.id = t.article_id WHERE t.transfer_from_article_id IS NOT NULL",
                        String.class)
                .forEach(incoming::add);
        assertThat(incoming).containsExactly("recovery-period");
    }
}
