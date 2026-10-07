package com.ssafy.thorp.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ssafy.thorp.learning.infrastructure.persistence.LearnerProgressRepositoryImpl;
import com.ssafy.thorp.learning.support.AdminCalls;
import com.ssafy.thorp.learning.support.LearningSeeds;
import com.ssafy.thorp.learning.support.LockWaits;
import com.ssafy.thorp.support.IntegrationTestSupport;
import com.ssafy.thorp.support.Requesters;
import com.ssafy.thorp.support.TestUsers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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
 * 나의 학습 API(learning-me-api §0~§8, data-model §11·§12, quickstart 10, tasks T181).
 *
 * <p>{@code me*.json} 다섯 픽스처는 시각 필드(값이 ISO 시각인 문자열)를 값 대신 자리만 보고 나머지를 JSON 동치로 대조한다({@code
 * docs/specs/learning-api/README.md}). 픽스처는 {@link #playScenario}가 만드는 상태 그대로다.
 *
 * <p>공개 상태는 {@link LearningSeeds}로 SQL로 심는다. 1·2단계를 공개하고 그 여덟 글을 확인 문제가 있는 CURRENT 판으로 게시한다. 게시
 * 유스케이스는 다른 IT가 본다. 사용자 행은 {@link TestUsers#ensure}로 준비한다. 나의 학습 테이블이 {@code users} 외래키를 건다.
 */
class LearnerApiIT extends IntegrationTestSupport {

    private static final Path EXAMPLES = Path.of("..", "docs", "specs", "learning-api", "examples");

    /** ISO 시각. 픽스처 대조에서 값 대신 자리만 본다. */
    private static final Pattern INSTANT =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");

    private static final long LEARNER_A = 9201;
    private static final long LEARNER_B = 9202;

    private static final Map<String, List<String>> TWO_QUESTIONS = quiz(2);
    private static final Map<String, List<String>> THREE_QUESTIONS = quiz(3);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    private LearningSeeds seeds;
    private AdminCalls calls;

    @BeforeEach
    void setUp() {
        seeds = new LearningSeeds(jdbcTemplate, objectMapper);
        seeds.reset();
        TestUsers.ensure(jdbcTemplate, LEARNER_A);
        TestUsers.ensure(jdbcTemplate, LEARNER_B);
        calls = new AdminCalls(mockMvc, objectMapper, 7);
    }

    @AfterEach
    void tearDown() {
        seeds.reset();
    }

    // ── 계약 픽스처 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("§1~§6 응답이 me*.json 픽스처와 같다(시각은 자리만 본다)")
    void matchesFixtures() throws Exception {
        seedTwoStages();

        Map<String, JsonNode> actual = playScenario(LEARNER_A);

        for (Map.Entry<String, JsonNode> entry : actual.entrySet()) {
            assertThat(masked(entry.getValue()))
                    .as(entry.getKey())
                    .isEqualTo(masked(example(entry.getKey())));
        }
        JsonNode me = actual.get("me.json");
        assertThat(me.get("stages")).hasSize(6);
        assertThat(me.get("badges")).hasSize(31);
        assertThat(me.get("articles")).hasSize(8);
        assertThat(me.at("/counts/badges").asInt())
                .isEqualTo(count(me.get("badges"), "status", "EARNED"));
    }

    @Test
    @DisplayName("레벨 길은 1·2단계 8편에서 레벨 4까지 보이고 N은 5다(quickstart 10-8)")
    void levelPathForTwoStages() throws Exception {
        seedTwoStages();

        JsonNode level = data(get("/learning/me"), LEARNER_A, 200).get("level");

        assertThat(level.get("current").asInt()).isEqualTo(1);
        assertThat(level.get("visibleMax").asInt()).isEqualTo(4);
        assertThat(level.get("unreachableFrom").asInt()).isEqualTo(5);
        assertThat(level.get("unreachableReason").asText()).isEqualTo("MORE_STAGES");
        assertThat(level.get("milestones")).hasSize(5);
        assertThat(level.get("reachedAt").isNull()).isTrue();
    }

    // ── 인증과 기록할 수 있는 글 ──────────────────────────────────────────

    @Test
    @DisplayName("비로그인은 401, 상태 변경에 CSRF 토큰이 없으면 403이다")
    void requiresLoginAndCsrf() throws Exception {
        seedTwoStages();

        for (MockHttpServletRequestBuilder request :
                List.of(get("/learning/me"), get("/learning/me/articles/etf-fees"))) {
            JsonNode anonymous = calls.call(request, 401);
            assertThat(anonymous.at("/error/code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        }
        // 상태 변경은 CSRF 토큰을 실어도 로그인하지 않았으면 401이다.
        for (MockHttpServletRequestBuilder request : stateChanges()) {
            JsonNode anonymous = calls.call(request.with(csrf()), 401);
            assertThat(anonymous.at("/error/code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        }
        for (MockHttpServletRequestBuilder request : stateChanges()) {
            JsonNode body = calls.call(request.with(Requesters.as(LEARNER_A)), 403);
            assertThat(body.at("/error/code").asText()).isEqualTo("CSRF_TOKEN_INVALID");
        }
        assertThat(rows("learning_progress", LEARNER_A)).isZero();
    }

    /** 상태를 바꾸는 다섯 요청. 로그인과 CSRF는 붙이지 않는다. */
    private static List<MockHttpServletRequestBuilder> stateChanges() {
        return List.of(
                position("etf-fees", 10),
                completion("etf-fees"),
                answer("etf-fees", "q1", "c1"),
                retry("etf-fees", "q1", "c1"),
                delete("/learning/me"));
    }

    @Test
    @DisplayName("첫 단계 공개 전에는 GET /learning/me와 기록 요청이 409 LEARNING_NOT_RECORDABLE이다")
    void offBeforeFirstStage() throws Exception {
        seeds.publishMinimal("returns-and-losses", "수익과 손실", "합성 질문", 8);

        for (MockHttpServletRequestBuilder request : recordRequests("returns-and-losses")) {
            assertThat(error(request, LEARNER_A, 409).get("code").asText())
                    .isEqualTo("LEARNING_NOT_RECORDABLE");
        }
        assertThat(error(get("/learning/me"), LEARNER_A, 409).get("code").asText())
                .isEqualTo("LEARNING_NOT_RECORDABLE");
        assertThat(rows("learning_progress", LEARNER_A)).isZero();
    }

    /** 글 하나를 가리키는 다섯 요청(글 기록·위치·완료·첫 답·다시 풀기). */
    private static List<MockHttpServletRequestBuilder> recordRequests(String articleId) {
        return List.of(
                get("/learning/me/articles/" + articleId),
                position(articleId, 10),
                completion(articleId),
                answer(articleId, "q1", "c1"),
                retry(articleId, "q1", "c1"));
    }

    @Test
    @DisplayName("이전 판 글은 409, 게시되지 않았거나 없는 글은 404다")
    void recordableArticlesOnly() throws Exception {
        seedTwoStages();
        seeds.publishMinimal("weights-and-rebalancing", "비중과 리밸런싱", "합성 질문", 2);

        for (MockHttpServletRequestBuilder request : recordRequests("weights-and-rebalancing")) {
            assertThat(error(request, LEARNER_A, 409).get("code").asText())
                    .isEqualTo("LEARNING_NOT_RECORDABLE");
        }
        for (String articleId : List.of("why-weight-cap", "no-such-article", "Bad_Id")) {
            for (MockHttpServletRequestBuilder request : recordRequests(articleId)) {
                assertThat(error(request, LEARNER_A, 404).get("code").asText())
                        .isEqualTo("LEARNING_ARTICLE_UNAVAILABLE");
            }
        }
        assertThat(rows("learning_progress", LEARNER_A)).isZero();
    }

    @Test
    @DisplayName("percent가 없거나 0~100 밖이면 400이다")
    void rejectsInvalidPercent() throws Exception {
        seedTwoStages();

        for (String body : List.of("{}", "{\"percent\":-1}", "{\"percent\":101}")) {
            assertThat(
                            error(
                                            put("/learning/me/articles/etf-fees/position")
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content(body),
                                            LEARNER_A,
                                            400)
                                    .get("code")
                                    .asText())
                    .isEqualTo("INVALID_INPUT");
        }
    }

    // ── 읽은 위치와 이어서 읽기 ───────────────────────────────────────────

    @Test
    @DisplayName("더 작은 비율은 기록을 줄이지 않고, 같은 값을 다시 보내도 마지막으로 연 글이 바뀐다(quickstart 10-2)")
    void positionKeepsFurthestAndMovesLastOpened() throws Exception {
        seedTwoStages();

        noContent(position("recovery-period", 62), LEARNER_A);
        noContent(position("recovery-period", 30), LEARNER_A);
        assertThat(article("recovery-period", LEARNER_A).get("percent").asInt()).isEqualTo(62);

        noContent(position("etf-fees", 40), LEARNER_A);
        assertThat(overview(LEARNER_A).at("/continueReading/articleId").asText())
                .isEqualTo("etf-fees");

        noContent(position("recovery-period", 62), LEARNER_A);
        JsonNode resume = overview(LEARNER_A).get("continueReading");
        assertThat(resume.get("kind").asText()).isEqualTo("RESUME");
        assertThat(resume.get("articleId").asText()).isEqualTo("recovery-period");
        assertThat(resume.get("number").asText()).isEqualTo("2-3");
        assertThat(resume.get("percent").asInt()).isEqualTo(62);
        assertThat(resume.get("moreStagesPreparing").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("행이 없는 글에 0을 보내도 '읽는 중'이고 비율은 1이다")
    void zeroPercentIsReading() throws Exception {
        seedTwoStages();

        noContent(position("etf-fees", 0), LEARNER_A);

        JsonNode record = article("etf-fees", LEARNER_A);
        assertThat(record.get("state").asText()).isEqualTo("READING");
        assertThat(record.get("percent").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("읽는 중 글이 없으면 읽는 순서상 첫 안 읽은 글을 맨 위에서 연다(START)")
    void startsFromFirstUnread() throws Exception {
        seedTwoStages();
        complete("sectors-and-etf", LEARNER_A);

        JsonNode next = overview(LEARNER_A).get("continueReading");

        assertThat(next.get("kind").asText()).isEqualTo("START");
        assertThat(next.get("articleId").asText()).isEqualTo("etf-tracking-difference");
        assertThat(next.get("number").asText()).isEqualTo("1-2");
        assertThat(next.get("percent").isNull()).isTrue();
    }

    @Test
    @DisplayName("공개된 글을 모두 완료하면 ALL_DONE이고, 공개되지 않은 단계가 있을 때만 moreStagesPreparing이다")
    void allDone() throws Exception {
        seeds.markStageReleased("etf-market-basics");
        seeds.publishCurrentWithQuiz("sectors-and-etf", "섹터와 ETF", List.of(), TWO_QUESTIONS);

        JsonNode done = data(completion("sectors-and-etf"), LEARNER_A, 200).get("continueReading");
        assertThat(done.get("kind").asText()).isEqualTo("ALL_DONE");
        assertThat(done.get("articleId").isNull()).isTrue();
        assertThat(done.get("percent").isNull()).isTrue();
        assertThat(done.get("moreStagesPreparing").asBoolean()).isTrue();

        for (String stage :
                List.of(
                        "returns-and-risk",
                        "allocation-rules",
                        "backtest-and-costs",
                        "pitfalls-and-validation",
                        "economy-and-regimes")) {
            seeds.markStageReleased(stage);
        }
        JsonNode all = overview(LEARNER_A).get("continueReading");
        assertThat(all.get("kind").asText()).isEqualTo("ALL_DONE");
        assertThat(all.get("moreStagesPreparing").asBoolean()).isFalse();
    }

    // ── 완료 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("완료는 멱등이다. 두 번째 요청은 그때의 시각과 빈 gained다")
    void completionIsIdempotent() throws Exception {
        seedTwoStages();

        JsonNode first = data(completion("etf-fees"), LEARNER_A, 200);
        JsonNode second = data(completion("etf-fees"), LEARNER_A, 200);

        assertThat(first.get("alreadyCompleted").asBoolean()).isFalse();
        assertThat(first.at("/gained/expTotal").asInt()).isEqualTo(20);
        assertThat(second.get("alreadyCompleted").asBoolean()).isTrue();
        assertThat(second.get("completedAt")).isEqualTo(first.get("completedAt"));
        assertThat(second.at("/gained/exp")).isEmpty();
        assertThat(second.at("/gained/badges")).isEmpty();
        assertThat(exp(LEARNER_A)).isEqualTo(20);
    }

    // ── 첫 답 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("완료하지 않은 글에 답하면 409 LEARNING_COMPLETION_REQUIRED다")
    void answerNeedsCompletion() throws Exception {
        seedTwoStages();
        noContent(position("etf-fees", 50), LEARNER_A);

        assertThat(error(answer("etf-fees", "q1", "c1"), LEARNER_A, 409).get("code").asText())
                .isEqualTo("LEARNING_COMPLETION_REQUIRED");
        assertThat(rows("learning_answer", LEARNER_A)).isZero();
    }

    @Test
    @DisplayName("첫 답을 저장한 뒤 정답과 풀이를 돌려주고, 다른 보기로 다시 보내면 저장된 답을 돌려준다")
    void firstAnswerIsKept() throws Exception {
        seedTwoStages();
        complete("etf-fees", LEARNER_A);

        JsonNode first = data(answer("etf-fees", "q1", "c2"), LEARNER_A, 200);
        assertThat(first.get("correct").asBoolean()).isFalse();
        assertThat(first.get("correctChoiceId").asText()).isEqualTo("c1");
        assertThat(first.get("explanation").asText()).isEqualTo("합성 풀이 q1");
        assertThat(first.get("alreadyAnswered").asBoolean()).isFalse();

        JsonNode again = data(answer("etf-fees", "q1", "c1"), LEARNER_A, 200);
        assertThat(again.get("alreadyAnswered").asBoolean()).isTrue();
        assertThat(again.get("choiceId").asText()).isEqualTo("c2");
        assertThat(again.get("correct").asBoolean()).isFalse();
        assertThat(again.at("/gained/exp")).isEmpty();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT choice_key FROM learning_answer WHERE user_id = ?",
                                String.class,
                                LEARNER_A))
                .isEqualTo("c2");
    }

    @Test
    @DisplayName("공개 판에 없는 문제나 그 문제에 없는 보기는 404 LEARNING_QUESTION_NOT_FOUND다")
    void unknownQuestionOrChoice() throws Exception {
        seedTwoStages();
        complete("etf-fees", LEARNER_A);

        for (MockHttpServletRequestBuilder request :
                List.of(
                        answer("etf-fees", "q9", "c1"),
                        answer("etf-fees", "q1", "c3"),
                        answer("etf-fees", "bad", "c1"))) {
            assertThat(error(request, LEARNER_A, 404).get("code").asText())
                    .isEqualTo("LEARNING_QUESTION_NOT_FOUND");
        }
    }

    @Test
    @DisplayName("첫 답·다시 풀기의 questionId나 choiceId가 비면 400 INVALID_INPUT이다")
    void rejectsBlankAnswerFields() throws Exception {
        seedTwoStages();
        complete("etf-fees", LEARNER_A);

        for (MockHttpServletRequestBuilder request :
                List.of(
                        answer("etf-fees", "", "c1"),
                        answer("etf-fees", "q1", " "),
                        retry("etf-fees", "", "c1"),
                        post("/learning/me/articles/etf-fees/answers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))) {
            assertThat(error(request, LEARNER_A, 400).get("code").asText())
                    .isEqualTo("INVALID_INPUT");
        }
        assertThat(rows("learning_answer", LEARNER_A)).isZero();
    }

    @Test
    @DisplayName("운영자가 문제를 바꿔 게시하면 남은 문제의 첫 답만 보이고, 바뀐 문제는 404이며, 경험치는 줄지 않는다(quickstart 7-5)")
    void questionsChangedByOperator() throws Exception {
        seedTwoStages();
        complete("recovery-period", LEARNER_A);
        data(answer("recovery-period", "q1", "c1"), LEARNER_A, 200);
        data(answer("recovery-period", "q2", "c3"), LEARNER_A, 200);
        int before = exp(LEARNER_A);

        // q1은 그대로, q2는 정답이 바뀌어 q7이 됐고, q3은 지웠다.
        Map<String, List<String>> changed = new LinkedHashMap<>();
        changed.put("q1", List.of("c1", "c2"));
        changed.put("q7", List.of("c8", "c3"));
        seeds.publishCurrentWithQuiz(
                "recovery-period", "떨어진 뒤 얼마나 걸려 돌아왔을까?", List.of("recovery-period"), changed);

        JsonNode record = article("recovery-period", LEARNER_A);
        assertThat(record.get("answers")).hasSize(1);
        assertThat(record.at("/answers/0/questionId").asText()).isEqualTo("q1");
        assertThat(record.at("/quiz/total").asInt()).isEqualTo(2);
        assertThat(record.at("/quiz/answered").asInt()).isEqualTo(1);
        assertThat(
                        error(answer("recovery-period", "q2", "c3"), LEARNER_A, 404)
                                .get("code")
                                .asText())
                .isEqualTo("LEARNING_QUESTION_NOT_FOUND");
        assertThat(exp(LEARNER_A)).isEqualTo(before);
    }

    @Test
    @DisplayName("같은 문제에 동시에 다른 보기를 보내도 첫 답은 한 행이고 두 응답이 같은 답을 보인다(SC-009)")
    void concurrentFirstAnswers() throws Exception {
        seedTwoStages();
        complete("etf-fees", LEARNER_A);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<JsonNode>> answers = new ArrayList<>();
            for (String choice : List.of("c1", "c2")) {
                Callable<JsonNode> task =
                        () -> {
                            start.await();
                            return data(answer("etf-fees", "q1", choice), LEARNER_A, 200);
                        };
                answers.add(pool.submit(task));
            }
            start.countDown();
            JsonNode one = answers.get(0).get(30, TimeUnit.SECONDS);
            JsonNode two = answers.get(1).get(30, TimeUnit.SECONDS);

            assertThat(one.get("choiceId")).isEqualTo(two.get("choiceId"));
            assertThat(one.get("alreadyAnswered").asBoolean())
                    .isNotEqualTo(two.get("alreadyAnswered").asBoolean());
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows("learning_answer", LEARNER_A)).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM learning_exp_entry WHERE user_id = ?"
                                        + " AND kind = 'ANSWER_CORRECT'",
                                Integer.class,
                                LEARNER_A))
                .isLessThanOrEqualTo(1);
    }

    // ── 경험치(quickstart 10-5) ───────────────────────────────────────────

    @Test
    @DisplayName("세 문제 글을 완료하고 모두 맞히면 100, 두 문제 글에서 하나만 맞히면 50이다")
    void expPerArticle() throws Exception {
        seedTwoStages();

        complete("recovery-period", LEARNER_A);
        for (String[] pick : new String[][] {{"q1", "c1"}, {"q2", "c3"}, {"q3", "c5"}}) {
            data(answer("recovery-period", pick[0], pick[1]), LEARNER_A, 200);
        }
        assertThat(exp(LEARNER_A)).isEqualTo(100);

        complete("etf-fees", LEARNER_B);
        data(answer("etf-fees", "q1", "c1"), LEARNER_B, 200);
        JsonNode wrong = data(answer("etf-fees", "q2", "c4"), LEARNER_B, 200);
        assertThat(exp(LEARNER_B)).isEqualTo(50);
        assertThat(wrong.at("/quiz/allAnswered").asBoolean()).isTrue();
        assertThat(wrong.at("/quiz/mastered").asBoolean()).isTrue();
        assertThat(wrong.at("/quiz/masteredVia").asText()).isEqualTo("FIRST_ANSWER");
    }

    @Test
    @DisplayName("모두 답한 글에 운영자가 더한 문제를 맞혀도 경험치는 0이고 정답 수에는 든다")
    void addedQuestionGivesNoExp() throws Exception {
        seedTwoStages();
        complete("etf-fees", LEARNER_A);
        data(answer("etf-fees", "q1", "c1"), LEARNER_A, 200);
        data(answer("etf-fees", "q2", "c3"), LEARNER_A, 200);
        int before = exp(LEARNER_A);

        seeds.publishCurrentWithQuiz(
                "etf-fees", "ETF 보수는 어디로 빠져나갈까?", List.of("total-expense"), THREE_QUESTIONS);
        JsonNode added = data(answer("etf-fees", "q3", "c5"), LEARNER_A, 200);

        assertThat(added.get("correct").asBoolean()).isTrue();
        assertThat(added.at("/gained/exp")).isEmpty();
        assertThat(exp(LEARNER_A)).isEqualTo(before);
        assertThat(overview(LEARNER_A).get("badges"))
                .anySatisfy(
                        badge -> {
                            assertThat(badge.get("badgeKey").asText()).isEqualTo("correct-10");
                            assertThat(badge.at("/progress/current").asInt()).isEqualTo(3);
                        });
    }

    // ── 다시 풀기 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("다시 풀기는 모두 답했고 첫 답이 모두 틀렸으며 익힘이 아닐 때만 열리고, 맞히면 RETRY 익힘과 끈기다")
    void retry() throws Exception {
        seedTwoStages();
        complete("etf-fees", LEARNER_A);
        data(answer("etf-fees", "q1", "c2"), LEARNER_A, 200);
        assertThat(error(retry("etf-fees", "q1", "c1"), LEARNER_A, 409).get("code").asText())
                .isEqualTo("LEARNING_RETRY_NOT_AVAILABLE");

        JsonNode allWrong = data(answer("etf-fees", "q2", "c4"), LEARNER_A, 200);
        assertThat(allWrong.at("/quiz/retryAvailable").asBoolean()).isTrue();
        int before = exp(LEARNER_A);
        // 다시 풀 수 있는 글에서 공개 판에 없는 문제나 보기는 404다.
        for (MockHttpServletRequestBuilder unknown :
                List.of(retry("etf-fees", "q9", "c1"), retry("etf-fees", "q1", "c3"))) {
            assertThat(error(unknown, LEARNER_A, 404).get("code").asText())
                    .isEqualTo("LEARNING_QUESTION_NOT_FOUND");
        }

        JsonNode miss = data(retry("etf-fees", "q1", "c2"), LEARNER_A, 200);
        assertThat(miss.get("correct").asBoolean()).isFalse();
        assertThat(miss.at("/quiz/mastered").asBoolean()).isFalse();
        assertThat(miss.has("alreadyAnswered")).isFalse();

        JsonNode hit = data(retry("etf-fees", "q2", "c3"), LEARNER_A, 200);
        assertThat(hit.get("correct").asBoolean()).isTrue();
        assertThat(hit.at("/quiz/masteredVia").asText()).isEqualTo("RETRY");
        assertThat(hit.at("/quiz/correct").asInt()).isZero();
        assertThat(hit.at("/gained/exp")).isEmpty();
        assertThat(hit.at("/gained/badges"))
                .extracting(badge -> badge.get("badgeKey").asText())
                .contains("persistence");
        assertThat(exp(LEARNER_A)).isEqualTo(before);
        assertThat(rows("learning_answer", LEARNER_A)).isEqualTo(2);

        assertThat(error(retry("etf-fees", "q1", "c1"), LEARNER_A, 409).get("code").asText())
                .isEqualTo("LEARNING_RETRY_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("첫 답에서 하나라도 맞힌 글은 다시 풀기를 받지 않는다")
    void noRetryAfterAnyCorrect() throws Exception {
        seedTwoStages();
        complete("etf-fees", LEARNER_A);
        data(answer("etf-fees", "q1", "c1"), LEARNER_A, 200);
        data(answer("etf-fees", "q2", "c4"), LEARNER_A, 200);

        assertThat(error(retry("etf-fees", "q2", "c3"), LEARNER_A, 409).get("code").asText())
                .isEqualTo("LEARNING_RETRY_NOT_AVAILABLE");
    }

    // ── 정산 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("운영자가 남은 글을 게시 취소하면 다음 GET에서 단계 완주 50과 배지가 생긴다(quickstart 10-7)")
    void unpublishCompletesStage() throws Exception {
        seedTwoStages();
        for (String article :
                List.of("returns-and-losses", "same-return-different-feel", "recovery-period")) {
            complete(article, LEARNER_B);
        }
        seeds.unpublish("volatility-is-not-loss");
        Instant opened = Instant.now().truncatedTo(ChronoUnit.MICROS);

        JsonNode me = overview(LEARNER_B);

        // 획득 날짜는 완료한 때가 아니라 기준이 채워진 뒤 연 때다(quickstart 10-7).
        assertThat(me.get("badges"))
                .anySatisfy(
                        badge -> {
                            assertThat(badge.get("badgeKey").asText())
                                    .isEqualTo("stage-returns-and-risk");
                            assertThat(badge.get("status").asText()).isEqualTo("EARNED");
                            assertThat(Instant.parse(badge.get("earnedAt").asText()))
                                    .isAfterOrEqualTo(opened);
                        });
        assertThat(me.at("/gained/exp/0/kind").asText()).isEqualTo("STAGE_COMPLETED");
        assertThat(me.at("/gained/exp/0/stageNumber").asInt()).isEqualTo(2);
        assertThat(me.at("/gained/exp/0/points").asInt()).isEqualTo(50);
        assertThat(me.at("/gained/badges"))
                .extracting(badge -> badge.get("badgeKey").asText())
                .contains("stage-returns-and-risk");
        assertThat(me.get("stages"))
                .anySatisfy(
                        stage -> {
                            assertThat(stage.get("number").asInt()).isEqualTo(2);
                            assertThat(stage.get("completed").asBoolean()).isTrue();
                            assertThat(stage.get("publishedCount").asInt()).isEqualTo(3);
                        });
        assertThat(overview(LEARNER_B).at("/gained/exp")).isEmpty();
    }

    @Test
    @DisplayName("게시된 글이 0편인 공개 단계는 REPREPARING이고 단계 배지는 STAGE_EMPTY다")
    void emptyReleasedStage() throws Exception {
        seedTwoStages();
        for (String article :
                List.of(
                        "returns-and-losses",
                        "same-return-different-feel",
                        "recovery-period",
                        "volatility-is-not-loss")) {
            seeds.unpublish(article);
        }

        JsonNode me = overview(LEARNER_A);

        assertThat(me.at("/stages/1/status").asText()).isEqualTo("REPREPARING");
        assertThat(me.at("/stages/1/completedCount").asInt()).isZero();
        assertThat(me.at("/stages/2/status").asText()).isEqualTo("PREPARING");
        assertThat(me.at("/stages/2/completedCount").isNull()).isTrue();
        assertThat(me.get("badges"))
                .anySatisfy(
                        badge -> {
                            assertThat(badge.get("badgeKey").asText())
                                    .isEqualTo("stage-returns-and-risk");
                            assertThat(badge.get("preparingReason").asText())
                                    .isEqualTo("STAGE_EMPTY");
                            assertThat(badge.get("progress").isNull()).isTrue();
                        });
    }

    // ── 학습 초기화 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("학습 초기화는 그 사용자의 네 테이블 행만 지우고 기록이 없어도 204다(quickstart 10-9)")
    void resetDeletesOnlyOwnRows() throws Exception {
        seedTwoStages();
        playScenario(LEARNER_A);
        playScenario(LEARNER_B);

        noContent(delete("/learning/me"), LEARNER_A);

        for (String table :
                List.of(
                        "learning_progress",
                        "learning_answer",
                        "learning_exp_entry",
                        "learning_badge_award")) {
            assertThat(rows(table, LEARNER_A)).as(table).isZero();
            assertThat(rows(table, LEARNER_B)).as(table).isPositive();
        }
        JsonNode me = overview(LEARNER_A);
        assertThat(me.at("/level/current").asInt()).isEqualTo(1);
        assertThat(me.at("/counts/badges").asInt()).isZero();
        assertThat(me.get("articles"))
                .allSatisfy(
                        article -> assertThat(article.get("state").asText()).isEqualTo("UNREAD"));

        noContent(delete("/learning/me"), LEARNER_A);
        assertThat(data(completion("etf-fees"), LEARNER_A, 200).at("/gained/expTotal").asInt())
                .isEqualTo(20);
    }

    // ── 문제가 바뀐 뒤의 정답 몫 상한(quickstart 10-5 넷째 줄) ──────────────

    @Test
    @DisplayName("세 문제 중 하나만 답한 뒤 정답이 바뀌어 새 문제가 되어도 나머지를 모두 맞힌 정답 몫 합은 60이다")
    void shareCapAfterAnswerChanged() throws Exception {
        seedTwoStages();
        complete("recovery-period", LEARNER_A);
        data(answer("recovery-period", "q1", "c1"), LEARNER_A, 200);

        // q2의 정답이 바뀌어 새 문제 q7이 됐다. 문제는 여전히 셋이다.
        Map<String, List<String>> changed = new LinkedHashMap<>();
        changed.put("q1", List.of("c1", "c2"));
        changed.put("q7", List.of("c7", "c8"));
        changed.put("q3", List.of("c5", "c6"));
        seeds.publishCurrentWithQuiz(
                "recovery-period", "떨어진 뒤 얼마나 걸려 돌아왔을까?", List.of("recovery-period"), changed);
        data(answer("recovery-period", "q3", "c5"), LEARNER_A, 200);
        JsonNode last = data(answer("recovery-period", "q7", "c7"), LEARNER_A, 200);

        assertThat(correctShare(LEARNER_A)).isEqualTo(60);
        assertThat(last.at("/quiz/allAnswered").asBoolean()).isTrue();
        assertThat(exp(LEARNER_A)).isEqualTo(100);
    }

    @Test
    @DisplayName("문제 수가 줄어 몫이 커져도 이미 받은 몫과 합해 60에서 멈춘다")
    void shareCapWhenQuestionCountShrinks() throws Exception {
        seedTwoStages();
        complete("recovery-period", LEARNER_A);
        data(answer("recovery-period", "q1", "c1"), LEARNER_A, 200);
        data(answer("recovery-period", "q2", "c3"), LEARNER_A, 200);
        assertThat(correctShare(LEARNER_A)).isEqualTo(40);

        // q2·q3을 지우고 새 문제 q9를 더해 두 문제가 됐다. 몫은 30이지만 남은 몫은 20이다.
        Map<String, List<String>> shrunk = new LinkedHashMap<>();
        shrunk.put("q1", List.of("c1", "c2"));
        shrunk.put("q9", List.of("c9", "c10"));
        seeds.publishCurrentWithQuiz(
                "recovery-period", "떨어진 뒤 얼마나 걸려 돌아왔을까?", List.of("recovery-period"), shrunk);
        JsonNode last = data(answer("recovery-period", "q9", "c9"), LEARNER_A, 200);

        assertThat(last.at("/gained/exp"))
                .anySatisfy(
                        exp -> {
                            assertThat(exp.get("kind").asText()).isEqualTo("ANSWER_CORRECT");
                            assertThat(exp.get("points").asInt()).isEqualTo(20);
                        });
        assertThat(correctShare(LEARNER_A)).isEqualTo(60);
        assertThat(exp(LEARNER_A)).isEqualTo(100);
    }

    // ── 받은 배지는 콘텐츠가 바뀌어도 남는다(quickstart 10-6) ──────────────

    @Test
    @DisplayName("단계를 완주한 뒤 그 단계에 글이 더해지면 배지와 경험치는 그대로이고 진행만 새 기준으로 보인다")
    void addedArticleKeepsStageBadge() throws Exception {
        seeds.markStageReleased("etf-market-basics");
        seeds.publishCurrentWithQuiz("sectors-and-etf", "섹터와 ETF", List.of(), TWO_QUESTIONS);
        seeds.publishCurrentWithQuiz(
                "etf-tracking-difference",
                "ETF 가격은 왜 지수와 똑같이 움직이지 않을까?",
                List.of(),
                THREE_QUESTIONS);
        seeds.publishCurrentWithQuiz(
                "etf-distributions", "분배금을 받으면 돈이 늘어난 걸까?", List.of(), TWO_QUESTIONS);
        complete("sectors-and-etf", LEARNER_A);
        complete("etf-tracking-difference", LEARNER_A);
        JsonNode third = data(completion("etf-distributions"), LEARNER_A, 200);
        assertThat(third.at("/stageProgress/stageCompleted").asBoolean()).isTrue();
        int before = exp(LEARNER_A);

        seeds.publishCurrentWithQuiz("etf-fees", "ETF 보수는 어디로 빠져나갈까?", List.of(), TWO_QUESTIONS);
        JsonNode me = overview(LEARNER_A);

        assertThat(me.at("/stages/0/completedCount").asInt()).isEqualTo(3);
        assertThat(me.at("/stages/0/publishedCount").asInt()).isEqualTo(4);
        assertThat(me.at("/stages/0/completed").asBoolean()).isFalse();
        assertThat(me.get("badges"))
                .anySatisfy(
                        badge -> {
                            assertThat(badge.get("badgeKey").asText())
                                    .isEqualTo("stage-etf-market-basics");
                            assertThat(badge.get("status").asText()).isEqualTo("EARNED");
                        });
        assertThat(exp(LEARNER_A)).isEqualTo(before);
    }

    // ── 사용자 단위 잠금(SC-015) ──────────────────────────────────────────

    @Test
    @DisplayName("나의 학습을 쓰는 요청은 모두 그 사용자의 잠금을 기다린다")
    void writesWaitForLearnerLock() throws Exception {
        seedTwoStages();
        complete("etf-fees", LEARNER_A);

        for (MockHttpServletRequestBuilder request :
                List.of(
                        get("/learning/me"),
                        position("etf-fees", 30),
                        completion("etf-fees"),
                        answer("etf-fees", "q1", "c1"),
                        retry("etf-fees", "q1", "c1"),
                        delete("/learning/me"))) {
            try (Connection holder = LockWaits.rawConnection(dataSource);
                    Connection watcher = LockWaits.rawConnection(dataSource)) {
                holder.setAutoCommit(false);
                lockLearner(holder, LEARNER_A);
                Future<JsonNode> pending = sendAsync(request, LEARNER_A);
                try {
                    LockWaits.awaitBlockedBy(holder, watcher);
                    assertThat(pending.isDone()).isFalse();
                } finally {
                    holder.rollback();
                }
                pending.get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName("다른 요청이 쓰는 동안 들어온 학습 초기화는 그 요청이 넣은 행까지 지운다")
    void resetWaitsForInFlightWrites() throws Exception {
        seedTwoStages();
        complete("etf-fees", LEARNER_A);

        try (Connection holder = LockWaits.rawConnection(dataSource);
                Connection watcher = LockWaits.rawConnection(dataSource)) {
            holder.setAutoCommit(false);
            // 첫 답 요청이 잠금을 잡고 답·장부·배지를 넣는 중이다.
            lockLearner(holder, LEARNER_A);
            Future<JsonNode> reset = sendAsync(delete("/learning/me"), LEARNER_A);
            try {
                LockWaits.awaitBlockedBy(holder, watcher);
                writeInFlight(holder, LEARNER_A);
                holder.commit();
            } catch (Exception | AssertionError failure) {
                holder.rollback();
                throw failure;
            }
            reset.get(30, TimeUnit.SECONDS);
        }

        for (String table :
                List.of(
                        "learning_progress",
                        "learning_answer",
                        "learning_exp_entry",
                        "learning_badge_award")) {
            assertThat(rows(table, LEARNER_A)).as(table).isZero();
        }
    }

    /** 커밋하지 않은 트랜잭션에서 그 사용자의 나의 학습 잠금을 잡는다. 서비스와 같은 식별자다. */
    private static void lockLearner(Connection holder, long user) throws Exception {
        try (PreparedStatement lock =
                holder.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            lock.setString(1, LearnerProgressRepositoryImpl.LOCK_PREFIX + user);
            lock.executeQuery().close();
        }
    }

    /** 잠금을 쥔 요청이 넣는 행. 첫 답 요청이 답·정답 몫·배지를 넣은 것과 같다. */
    private static void writeInFlight(Connection holder, long user) throws Exception {
        try (PreparedStatement answer =
                        holder.prepareStatement(
                                "INSERT INTO learning_answer (user_id, article_id, question_key,"
                                        + " choice_key, correct, answered_at) VALUES (?, (SELECT id"
                                        + " FROM learning_article WHERE article_key = 'etf-fees'),"
                                        + " 'q1', 'c1', true, now())");
                PreparedStatement ledger =
                        holder.prepareStatement(
                                "INSERT INTO learning_exp_entry (user_id, kind, ref_key, article_id,"
                                        + " points, earned_at) VALUES (?, 'ANSWER_CORRECT',"
                                        + " 'answer:etf-fees:q1', (SELECT id FROM learning_article"
                                        + " WHERE article_key = 'etf-fees'), 30, now())");
                PreparedStatement badge =
                        holder.prepareStatement(
                                "INSERT INTO learning_badge_award (user_id, badge_key, earned_at)"
                                        + " VALUES (?, 'correct-1', now())")) {
            for (PreparedStatement statement : List.of(answer, ledger, badge)) {
                statement.setLong(1, user);
                statement.executeUpdate();
            }
        }
    }

    /** 로그인과 CSRF를 붙인 요청을 다른 스레드에서 보낸다. 상태는 기대하지 않는다. */
    private Future<JsonNode> sendAsync(MockHttpServletRequestBuilder request, long user) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            return pool.submit(() -> calls.callAny(request.with(Requesters.as(user)).with(csrf())));
        } finally {
            pool.shutdown();
        }
    }

    // ── 시나리오 ──────────────────────────────────────────────────────────

    /** 1·2단계를 공개하고 그 여덟 글을 확인 문제가 있는 CURRENT 판으로 게시한다. 3~6단계는 공개 전이다. 공개 용어는 열 개다. */
    private void seedTwoStages() {
        seeds.markStageReleased("etf-market-basics");
        seeds.markStageReleased("returns-and-risk");
        seeds.publishCurrentWithQuiz(
                "sectors-and-etf",
                "섹터와 ETF",
                List.of("sector", "representative-etf"),
                TWO_QUESTIONS);
        seeds.publishCurrentWithQuiz(
                "etf-tracking-difference",
                "ETF 가격은 왜 지수와 똑같이 움직이지 않을까?",
                List.of("tracking-difference"),
                THREE_QUESTIONS);
        seeds.publishCurrentWithQuiz(
                "etf-distributions", "분배금을 받으면 돈이 늘어난 걸까?", List.of("distribution"), TWO_QUESTIONS);
        seeds.publishCurrentWithQuiz(
                "etf-fees", "ETF 보수는 어디로 빠져나갈까?", List.of("total-expense"), TWO_QUESTIONS);
        seeds.publishCurrentWithQuiz(
                "returns-and-losses",
                "수익과 손실",
                List.of("net-total-return", "max-drawdown"),
                TWO_QUESTIONS);
        seeds.publishCurrentWithQuiz(
                "same-return-different-feel",
                "같은 수익률인데 왜 느낌이 다를까?",
                List.of("cumulative-return"),
                TWO_QUESTIONS);
        seeds.publishCurrentWithQuiz(
                "recovery-period",
                "떨어진 뒤 얼마나 걸려 돌아왔을까?",
                List.of("recovery-period"),
                THREE_QUESTIONS);
        seeds.publishCurrentWithQuiz(
                "volatility-is-not-loss", "변동성은 손실일까?", List.of("volatility"), TWO_QUESTIONS);
    }

    /**
     * 픽스처의 상태를 만든다. 2단계 첫 글을 완료하고, 1단계 네 글을 차례로 완료하며 문제에 답한다. 마지막으로 2-3 글을 62%까지 읽는다.
     *
     * @return 픽스처 파일 이름 → 그 요청의 {@code data}
     */
    private Map<String, JsonNode> playScenario(long user) throws Exception {
        Map<String, JsonNode> captured = new LinkedHashMap<>();
        complete("returns-and-losses", user);

        complete("sectors-and-etf", user);
        data(answer("sectors-and-etf", "q1", "c1"), user, 200);
        data(answer("sectors-and-etf", "q2", "c3"), user, 200);

        complete("etf-tracking-difference", user);
        data(answer("etf-tracking-difference", "q1", "c1"), user, 200);
        captured.put(
                "me-answer.json", data(answer("etf-tracking-difference", "q2", "c4"), user, 200));
        data(answer("etf-tracking-difference", "q3", "c6"), user, 200);

        complete("etf-distributions", user);
        data(answer("etf-distributions", "q1", "c2"), user, 200);
        data(answer("etf-distributions", "q2", "c4"), user, 200);
        captured.put("me-retry.json", data(retry("etf-distributions", "q1", "c1"), user, 200));

        captured.put("me-completion.json", data(completion("etf-fees"), user, 200));
        noContent(position("recovery-period", 62), user);

        captured.put("me-article.json", article("etf-tracking-difference", user));
        captured.put("me.json", overview(user));
        return captured;
    }

    // ── 호출 ──────────────────────────────────────────────────────────────

    private JsonNode overview(long user) throws Exception {
        return data(get("/learning/me"), user, 200);
    }

    private JsonNode article(String articleId, long user) throws Exception {
        return data(get("/learning/me/articles/" + articleId), user, 200);
    }

    private void complete(String articleId, long user) throws Exception {
        data(completion(articleId), user, 200);
    }

    private static MockHttpServletRequestBuilder completion(String articleId) {
        return post("/learning/me/articles/" + articleId + "/completion");
    }

    private static MockHttpServletRequestBuilder position(String articleId, int percent) {
        return put("/learning/me/articles/" + articleId + "/position")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"percent\":" + percent + "}");
    }

    private static MockHttpServletRequestBuilder answer(
            String articleId, String questionId, String choiceId) {
        return post("/learning/me/articles/" + articleId + "/answers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"questionId\":\""
                                + questionId
                                + "\",\"choiceId\":\""
                                + choiceId
                                + "\"}");
    }

    private static MockHttpServletRequestBuilder retry(
            String articleId, String questionId, String choiceId) {
        return post("/learning/me/articles/" + articleId + "/retry-answers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"questionId\":\""
                                + questionId
                                + "\",\"choiceId\":\""
                                + choiceId
                                + "\"}");
    }

    /** 로그인과 CSRF를 붙인 요청. 성공 봉투의 {@code data}다. */
    private JsonNode data(MockHttpServletRequestBuilder request, long user, int status)
            throws Exception {
        JsonNode body = calls.call(request.with(Requesters.as(user)).with(csrf()), status);
        assertThat(body.get("success").asBoolean()).isTrue();
        return body.get("data");
    }

    private JsonNode error(MockHttpServletRequestBuilder request, long user, int status)
            throws Exception {
        JsonNode body = calls.call(request.with(Requesters.as(user)).with(csrf()), status);
        assertThat(body.get("success").asBoolean()).isFalse();
        return body.get("error");
    }

    private void noContent(MockHttpServletRequestBuilder request, long user) throws Exception {
        mockMvc.perform(request.with(Requesters.as(user)).with(csrf()))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNoContent());
    }

    // ── 대조 ──────────────────────────────────────────────────────────────

    private JsonNode example(String file) throws Exception {
        return objectMapper.readTree(Files.readString(EXAMPLES.resolve(file)));
    }

    /** 값이 ISO 시각인 문자열을 자리표시로 바꾼다. 실행마다 달라지는 값이다. */
    private static JsonNode masked(JsonNode node) {
        JsonNode copy = node.deepCopy();
        mask(copy);
        return copy;
    }

    private static void mask(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<String> times = new ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isTextual()
                        && INSTANT.matcher(field.getValue().asText()).matches()) {
                    times.add(field.getKey());
                } else {
                    mask(field.getValue());
                }
            }
            times.forEach(key -> object.set(key, TextNode.valueOf("<time>")));
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            array.forEach(LearnerApiIT::mask);
        }
    }

    private static int count(JsonNode array, String field, String value) {
        int count = 0;
        for (JsonNode item : array) {
            if (value.equals(item.path(field).asText())) {
                count++;
            }
        }
        return count;
    }

    private int rows(String table, long user) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, user);
    }

    /** 정답 몫({@code ANSWER_CORRECT}) 합. */
    private int correctShare(long user) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(points), 0) FROM learning_exp_entry"
                        + " WHERE user_id = ? AND kind = 'ANSWER_CORRECT'",
                Integer.class,
                user);
    }

    private int exp(long user) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(points), 0) FROM learning_exp_entry WHERE user_id = ?",
                Integer.class,
                user);
    }

    /** 문제 {@code n}개. 문제마다 보기 둘이고 첫 보기가 정답이다(q1: c1·c2, q2: c3·c4, q3: c5·c6). */
    private static Map<String, List<String>> quiz(int questions) {
        Map<String, List<String>> quiz = new LinkedHashMap<>();
        for (int index = 1; index <= questions; index++) {
            quiz.put("q" + index, List.of("c" + (index * 2 - 1), "c" + (index * 2)));
        }
        return quiz;
    }
}
