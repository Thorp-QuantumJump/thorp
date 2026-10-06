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
import com.ssafy.thorp.learning.support.V2Draft;
import com.ssafy.thorp.support.IntegrationTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 배우기 관리 API의 2판 경우(learning-admin-api §3~§8, quickstart 1 표·6-2·7-2~7-6, tasks T144·T160). 9월
 * 판(V1) 흐름은 {@code LearningAdminApiIT}가 본다.
 *
 * <p>{@code publish-check-failed-v2.json}·{@code admin-preview.json}은 JSON 동치, {@code
 * admin-article-v2.json}은 키 구성으로 대조한다({@code docs/specs/learning-api/README.md}).
 *
 * <p>글마다 게시의 경우를 따로 보려고 공개된 단계는 {@link LearningSeeds#markStageReleased}로 표시한다. 단계 공개는 {@code
 * LearningStageReleaseIT}가 본다. V2 초안이 기존 용어 {@code volatility}를 쓰는 경우는 넘겨받기를 거치지 않도록 옛 소유 글({@code
 * inverse-volatility})의 초안에서 그 용어를 먼저 뺀다({@link #freeVolatilityTerm}). 넘겨받기는 {@code
 * LearningTermTransferIT}가 본다.
 *
 * <p><b>Spring 컨텍스트를 새로 만들지 않는다.</b> {@link IntegrationTestSupport}의 설정을 그대로 물려받는다. 관리 계정 7은 {@code
 * PostgresTestSupport}가 준다.
 */
class LearningAdminV2ApiIT extends IntegrationTestSupport {

    /** gradle test의 작업 디렉터리는 backend/다. */
    private static final Path EXAMPLES = Path.of("..", "docs", "specs", "learning-api", "examples");

    private static final long ADMIN = 7;
    private static final String V2_ARTICLE = "volatility-is-not-loss";
    private static final String V1_ARTICLE = "returns-and-losses";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

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

    private JsonNode example(String file) throws Exception {
        return objectMapper.readTree(Files.readString(EXAMPLES.resolve(file)));
    }

    private static MockHttpServletRequestBuilder save(String articleId, JsonNode body) {
        return put("/admin/learning/articles/" + articleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString());
    }

    private static MockHttpServletRequestBuilder publish(
            String articleId, int expectedDraftVersion) {
        return post("/admin/learning/articles/" + articleId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedDraftVersion\":" + expectedDraftVersion + "}");
    }

    private JsonNode publicData(String url) throws Exception {
        return calls.call(get(url), 200).get("data");
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static List<String> fieldsOf(JsonNode error) {
        List<String> fields = new ArrayList<>();
        error.at("/details/fields").forEach(field -> fields.add(field.get("field").asText()));
        return fields;
    }

    /** 넘겨받기를 거치지 않도록 옛 소유 글의 초안에서 {@code volatility}를 빼 둔다. {@link LearningSeeds#reset()}이 되돌린다. */
    private void freeVolatilityTerm() {
        jdbcTemplate.update("DELETE FROM learning_term WHERE term_key = 'volatility'");
    }

    private void seedFullSectorMap() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("SEMICONDUCTOR", "반도체");
        names.put("BATTERY", "2차전지");
        seeds.seedSectorMap(names);
    }

    // --- 요청 본문 ---

    /**
     * {@code admin-article-v2.json}의 초안을 저장 요청으로 바꾼다. 문제·보기 식별자는 서버가 주므로 비우고, 넘겨받기는 뺀다(넘겨받기는 {@code
     * LearningTermTransferIT}).
     */
    private ObjectNode fixtureDraft(int expectedDraftVersion) throws Exception {
        ObjectNode body = (ObjectNode) example("admin-article-v2.json").get("draft").deepCopy();
        body.put("expectedDraftVersion", expectedDraftVersion);
        body.get("terms").forEach(term -> ((ObjectNode) term).remove("transferFrom"));
        for (JsonNode question : body.get("quiz")) {
            ((ObjectNode) question).putNull("questionId");
            question.get("choices").forEach(choice -> ((ObjectNode) choice).putNull("choiceId"));
        }
        return body;
    }

    private static ObjectNode text(ArrayNode runs, String text) {
        return runs.addObject().put("text", text);
    }

    private ObjectNode v2Base(int expectedDraftVersion) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedDraftVersion", expectedDraftVersion);
        body.put("title", "변동성은 손실일까?");
        body.put("question", "값이 크게 오르내렸다는 것은 돈을 잃었다는 뜻일까?");
        body.put("readingOrder", 204);
        body.put("format", "V2");
        body.put("stageKey", "returns-and-risk");
        body.put("stagePosition", 4);
        body.putNull("example");
        body.putArray("sources");
        return body;
    }

    private static void keySummary(ArrayNode blocks, String... lines) {
        ArrayNode summary = blocks.addObject().put("type", "keySummary").putArray("lines");
        for (String line : lines) {
            text(summary.addArray(), line);
        }
    }

    private static void heading(ArrayNode blocks, String text) {
        blocks.addObject().put("type", "heading").put("text", text);
    }

    private static ArrayNode paragraph(ArrayNode blocks) {
        return blocks.addObject().put("type", "paragraph").putArray("content");
    }

    private static void hypothetical(ArrayNode blocks, String text) {
        text(blocks.addObject().put("type", "hypothetical").putArray("content"), text);
    }

    private static void misconception(ArrayNode blocks, String wrong, String correction) {
        ObjectNode block = blocks.addObject().put("type", "misconception");
        text(block.putArray("wrong"), wrong);
        text(block.putArray("correction"), correction);
    }

    private static ObjectNode question(ArrayNode quiz, String prompt, String explanation) {
        ObjectNode question = quiz.addObject();
        question.putNull("questionId");
        question.put("prompt", prompt);
        question.putArray("choices");
        question.put("explanation", explanation);
        return question;
    }

    private static void choice(ObjectNode question, String id, String text, boolean correct) {
        ObjectNode choice = ((ArrayNode) question.get("choices")).addObject();
        if (id == null) {
            choice.putNull("choiceId");
        } else {
            choice.put("choiceId", id);
        }
        choice.put("text", text).put("correct", correct);
    }

    /**
     * v2 검사 열일곱 가지를 모두 통과하는 {@code volatility-is-not-loss} 초안({@link V2Draft}). {@code volatility}를
     * 소유한다.
     *
     * @param questionIds 두 문제의 식별자. null이면 서버가 준다
     * @param choiceIds 네 보기의 식별자. null이면 서버가 준다
     * @param secondCorrect 둘째 문제의 정답 보기(0 또는 1). 같은 문제 판정을 바꿀 때 쓴다
     */
    private ObjectNode passingDraft(
            int expectedDraftVersion,
            List<String> questionIds,
            List<String> choiceIds,
            int secondCorrect) {
        V2Draft draft =
                V2Draft.of(objectMapper, "변동성은 손실일까?", "returns-and-risk", 4)
                        .question("값이 크게 오르내렸다는 것은 돈을 잃었다는 뜻일까?")
                        .readingOrder(204)
                        .term("volatility", "변동성", "값이 오르내리는 정도다.", "volatility")
                        .secondQuestionCorrectChoice(secondCorrect);
        if (questionIds != null) {
            draft.quizIds(questionIds, choiceIds);
        }
        return draft.build(expectedDraftVersion);
    }

    /**
     * {@code publish-check-failed-v2.json}의 세 항목만 걸리는 초안. 본문은 정확히 1,520자(3분)이고, 블록 4 문단에 {@code
     * -30%}, 둘째 문제(식별자 {@code q4})에 정답이 둘이다. 나머지 열네 검사는 통과한다.
     */
    private ObjectNode failingDraft(int expectedDraftVersion) {
        ObjectNode body = v2Base(expectedDraftVersion);
        ArrayNode blocks = body.putArray("blocks");
        keySummary(blocks, "요약 하나", "요약 둘", "요약 셋"); // 10자
        heading(blocks, "첫째 소제목"); // 5자
        heading(blocks, "둘째 소제목"); // 5자
        heading(blocks, "셋째 소제목"); // 5자
        text(paragraph(blocks), "고점 대비 -30% 줄었다"); // 11자, 블록 4
        hypothetical(blocks, "가상 예"); // 3자
        misconception(blocks, "오해 문장", "바로잡는 문장"); // 10자
        text(paragraph(blocks), "가".repeat(1471)); // 합계 1,520자
        body.putArray("terms");
        ArrayNode quiz = body.putArray("quiz");
        ObjectNode first = question(quiz, "질문 하나", "풀이");
        choice(first, null, "가", true);
        choice(first, null, "나", false);
        ObjectNode second = question(quiz, "질문 둘", "풀이 둘");
        choice(second, null, "다", true);
        choice(second, null, "라", true);
        return body;
    }

    /** 객체는 키 집합을, 배열은 양쪽 첫 원소를 비교한다. 예시가 객체인데 실제 값이 null이면 건너뛴다. */
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

    // --- §3 만들기 ---

    @Test
    @DisplayName(
            "stageKey 없는 9월 판 만들기 요청은 400, 없는 단계는 404 LEARNING_STAGE_NOT_FOUND다 (quickstart 6-2)")
    void createNeedsExistingStage() throws Exception {
        JsonNode legacyRequest =
                calls.adminError(
                        post("/admin/learning/articles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"articleId\":\"etf-tax-basics\",\"title\":\"t\",\"readingOrder\":10}"),
                        400);
        assertThat(fieldsOf(legacyRequest)).contains("stageKey", "stagePosition");

        JsonNode unknownStage =
                calls.adminError(
                        post("/admin/learning/articles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"articleId\":\"etf-tax-basics\",\"title\":\"t\","
                                                + "\"stageKey\":\"no-such-stage\",\"stagePosition\":1}"),
                        404);
        assertThat(unknownStage.get("code").asText()).isEqualTo("LEARNING_STAGE_NOT_FOUND");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM learning_article WHERE article_key = 'etf-tax-basics'",
                                Integer.class))
                .isZero();
    }

    // --- §4·§5 V2 초안 ---

    @Test
    @DisplayName("V2 초안 저장은 새 문제·보기에 식별자를 주고 admin-article-v2.json과 키 구성이 같다")
    void saveV2DraftAssignsQuizIds() throws Exception {
        freeVolatilityTerm();

        JsonNode saved = calls.adminData(save(V2_ARTICLE, fixtureDraft(0)), 200);

        assertSameShape(saved, example("admin-article-v2.json"), "$");
        assertThat(saved.get("draftVersion").asInt()).isEqualTo(1);
        assertThat(saved.at("/draft/format").asText()).isEqualTo("V2");
        assertThat(saved.at("/draft/quiz/0/questionId").asText()).isEqualTo("q1");
        assertThat(saved.at("/draft/quiz/0/choices"))
                .extracting(choice -> choice.get("choiceId").asText())
                .containsExactly("c2", "c3");
        assertThat(saved.at("/draft/terms/0/transferFrom").isNull()).isTrue();
        assertThat(saved.at("/draft/terms/0").has("sentence")).isFalse();
        assertThat(saved.at("/draft/blocks/2/content/1"))
                .as("초안의 언급 조각에는 owned·aliases가 없다")
                .isEqualTo(
                        objectMapper.readTree(
                                "{\"term\":\"max-drawdown\",\"text\":\"가장 크게 줄었던 폭\"}"));
        assertThat(saved.at("/draft/blocks/9/asOf").isNull()).isTrue();
        assertThat(calls.adminData(get("/admin/learning/articles/" + V2_ARTICLE), 200))
                .isEqualTo(saved);
        // 키 구성 대조는 배열의 첫 원소만 본다. 블록 열 종류 전부의 초안 모양은 저장한 요청과 값으로 맞춘다.
        ObjectNode echoed = (ObjectNode) saved.get("draft").deepCopy();
        echoed.get("terms").forEach(term -> ((ObjectNode) term).remove("transferFrom"));
        for (JsonNode question : echoed.get("quiz")) {
            ((ObjectNode) question).putNull("questionId");
            question.get("choices").forEach(choice -> ((ObjectNode) choice).putNull("choiceId"));
        }
        ObjectNode request = fixtureDraft(0);
        request.remove("expectedDraftVersion");
        assertThat(echoed).isEqualTo(request);
    }

    @Test
    @DisplayName("미리 보기는 admin-preview.json과 같고 아무것도 쓰지 않는다 (quickstart 7-4)")
    void previewMatchesExample() throws Exception {
        freeVolatilityTerm();
        calls.adminData(save(V2_ARTICLE, fixtureDraft(0)), 200);

        JsonNode preview =
                calls.adminData(get("/admin/learning/articles/" + V2_ARTICLE + "/preview"), 200);

        assertThat(preview).isEqualTo(example("admin-preview.json"));
        assertThat(count("learning_article_revision")).isZero();
        assertThat(count("learning_published_term")).isZero();
        assertThat(
                        calls.call(get("/learning/articles/" + V2_ARTICLE), 404)
                                .at("/error/code")
                                .asText())
                .isEqualTo("LEARNING_ARTICLE_UNAVAILABLE");
    }

    @Test
    @DisplayName("V1 초안에 V2 요소, V2 초안에 sentence·sources를 넣으면 400이고 위치가 실린다")
    void formatShapeViolationsAreListed() throws Exception {
        ObjectNode v1WithV2Block = objectMapper.createObjectNode();
        v1WithV2Block.put("expectedDraftVersion", 0);
        v1WithV2Block.put("title", "수익과 손실");
        v1WithV2Block.put("question", "q");
        v1WithV2Block.put("readingOrder", 8);
        keySummary(v1WithV2Block.putArray("blocks"), "한 줄");
        v1WithV2Block.putArray("sources");
        v1WithV2Block.putArray("terms");
        JsonNode v1Error = calls.adminError(save(V1_ARTICLE, v1WithV2Block), 400);
        assertThat(v1Error.get("code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(fieldsOf(v1Error)).containsExactly("blocks[0]");

        freeVolatilityTerm();
        ObjectNode v2WithV1Parts = fixtureDraft(0);
        ((ObjectNode) v2WithV1Parts.get("terms").get(0)).put("sentence", "V1 설명 문장");
        ((ArrayNode) v2WithV1Parts.get("sources")).addObject().put("label", "출처");
        JsonNode v2Error = calls.adminError(save(V2_ARTICLE, v2WithV1Parts), 400);
        assertThat(fieldsOf(v2Error)).containsExactlyInAnyOrder("sources", "terms[0].sentence");

        ObjectNode bothShapes = fixtureDraft(0);
        ((ObjectNode) bothShapes.get("blocks").get(2)).put("text", "문단 글자");
        JsonNode bothError = calls.adminError(save(V2_ARTICLE, bothShapes), 400);
        assertThat(fieldsOf(bothError)).containsExactly("blocks[2]");
        assertThat(
                        calls.adminData(get("/admin/learning/articles/" + V2_ARTICLE), 200)
                                .get("draftVersion")
                                .asInt())
                .isZero();
    }

    @Test
    @DisplayName("글자 조각 필드의 글자 수·허용 조각 종류·출처 위치 위반은 그 필드 자리로 400이다")
    void richTextFieldViolationsAreLocated() throws Exception {
        freeVolatilityTerm();
        ObjectNode body = fixtureDraft(0);
        ((ObjectNode) body.at("/blocks/0/lines/0/0")).put("text", "가".repeat(121));
        ((ArrayNode) body.at("/blocks/6/items/0"))
                .addObject()
                .put("term", "max-drawdown")
                .put("text", "가장 크게 줄었던 폭");
        // 출처 조각을 받는 문단에 넣어야 조각 종류가 아니라 첫 조각 위치 규칙에 걸린다.
        ArrayNode leadingSource = (ArrayNode) body.at("/blocks/2/content");
        leadingSource
                .insertObject(0)
                .putObject("source")
                .put("label", "출처")
                .put("url", "https://example.com");

        JsonNode error = calls.adminError(save(V2_ARTICLE, body), 400);

        assertThat(fieldsOf(error))
                .containsExactlyInAnyOrder(
                        "blocks[0].lines[0]", "blocks[2].content", "blocks[6].items[0]");
    }

    @Test
    @DisplayName("글자 조각의 모르는 키와 조각 하나에 키 둘은 400이다")
    void unknownRunKeysAreRejected() throws Exception {
        freeVolatilityTerm();
        ObjectNode unknownKey = fixtureDraft(0);
        ((ObjectNode) unknownKey.at("/blocks/2/content/0")).put("bold", true);
        assertThat(fieldsOf(calls.adminError(save(V2_ARTICLE, unknownKey), 400)))
                .containsExactly("blocks[2].content[0]");

        ObjectNode twoKinds = fixtureDraft(0);
        ((ObjectNode) twoKinds.at("/blocks/2/content/0"))
                .putObject("source")
                .put("label", "출처")
                .put("url", "https://example.com");
        assertThat(fieldsOf(calls.adminError(save(V2_ARTICLE, twoKinds), 400)))
                .containsExactly("blocks[2].content[0]");
    }

    @Test
    @DisplayName("LEGACY 판이 없는 V2 글을 V1로 되돌리면 409 LEARNING_FORMAT_OUTDATED다 (quickstart 6-2)")
    void revertingNewArticleToV1IsOutdated() throws Exception {
        ObjectNode v1Body = objectMapper.createObjectNode();
        v1Body.put("expectedDraftVersion", 0);
        v1Body.put("title", "변동성은 손실일까?");
        v1Body.put("question", "q");
        v1Body.put("readingOrder", 204);
        v1Body.put("format", "V1");
        v1Body.putArray("blocks").addObject().put("type", "paragraph").put("text", "문단");
        v1Body.putArray("sources");
        v1Body.putArray("terms");

        JsonNode error = calls.adminError(save(V2_ARTICLE, v1Body), 409);

        assertThat(error.get("code").asText()).isEqualTo("LEARNING_FORMAT_OUTDATED");
    }

    @Test
    @DisplayName("설명 문장이 채워진 V1 글을 V2로 다시 쓰고, LEGACY 판이 있으니 V1로 되돌릴 수 있다 (quickstart 6-2)")
    void rewritesFilledV1ArticleAndRevertsIt() throws Exception {
        // 운영의 1판 용어는 설명 문장이 채워져 있다. 마이그레이션 시드는 빈 문장이라 V2 모양에도 맞아 이 경우를 가린다.
        jdbcTemplate.update(
                "UPDATE learning_term SET sentence = '고점에서 가장 크게 줄었던 폭이다.'"
                        + " WHERE term_key IN ('max-drawdown', 'net-total-return')");
        seeds.publishMinimal("returns-and-losses", "수익과 손실", "q", 8);
        ObjectNode v2Body = objectMapper.createObjectNode();
        v2Body.put("expectedDraftVersion", 0);
        v2Body.put("title", "수익과 손실");
        v2Body.put("question", "q");
        v2Body.put("readingOrder", 201);
        v2Body.put("format", "V2");
        text(paragraph(v2Body.putArray("blocks")), "문단");
        v2Body.putNull("example");
        v2Body.putArray("sources");
        v2Body.putArray("terms")
                .addObject()
                .put("termId", "max-drawdown")
                .put("label", "가장 크게 줄었던 폭")
                .put("meaning", "고점에서 가장 크게 줄었던 폭이다.")
                .put("reading", "")
                .put("caveat", "")
                .putArray("aliases");
        v2Body.putArray("quiz");

        JsonNode rewritten = calls.adminData(save("returns-and-losses", v2Body), 200);

        assertThat(rewritten.at("/draft/format").asText()).isEqualTo("V2");
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT term_key || ':' || sentence || ':' || meaning FROM learning_term"
                                        + " WHERE term_key IN ('max-drawdown', 'net-total-return')",
                                String.class))
                .containsExactly("max-drawdown::고점에서 가장 크게 줄었던 폭이다.");

        ObjectNode v1Body = objectMapper.createObjectNode();
        v1Body.put("expectedDraftVersion", rewritten.get("draftVersion").asInt());
        v1Body.put("title", "수익과 손실");
        v1Body.put("question", "q");
        v1Body.put("readingOrder", 8);
        v1Body.put("format", "V1");
        v1Body.putArray("blocks").addObject().put("type", "paragraph").put("text", "문단");
        v1Body.putArray("sources");
        v1Body.putArray("terms")
                .addObject()
                .put("termId", "max-drawdown")
                .put("label", "가장 크게 줄었던 폭")
                .put("sentence", "고점에서 가장 크게 줄었던 폭이다.");

        JsonNode reverted = calls.adminData(save("returns-and-losses", v1Body), 200);

        assertThat(reverted.at("/draft/format").asText()).isEqualTo("V1");
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT term_key || ':' || sentence || ':' || meaning FROM learning_term"
                                        + " WHERE term_key = 'max-drawdown'",
                                String.class))
                .containsExactly("max-drawdown:고점에서 가장 크게 줄었던 폭이다.:");
    }

    @Test
    @DisplayName("지금 초안에 없는 문제·보기 식별자는 400이고, 없는 stageKey는 404다")
    void fabricatedIdsAndUnknownStage() throws Exception {
        freeVolatilityTerm();
        ObjectNode fabricated = fixtureDraft(0);
        ((ObjectNode) fabricated.at("/quiz/0")).put("questionId", "q9");
        ((ObjectNode) fabricated.at("/quiz/0/choices/0")).put("choiceId", "c9");
        assertThat(fieldsOf(calls.adminError(save(V2_ARTICLE, fabricated), 400)))
                .containsExactly("quiz[0].questionId", "quiz[0].choices[0].choiceId");

        ObjectNode unknownStage = fixtureDraft(0);
        unknownStage.put("stageKey", "no-such-stage");
        assertThat(calls.adminError(save(V2_ARTICLE, unknownStage), 404).get("code").asText())
                .isEqualTo("LEARNING_STAGE_NOT_FOUND");
    }

    // --- §6 게시 방식 ---

    @Test
    @DisplayName("V2 글은 초안 단계가 공개 전이면 409, V1 글은 초안 단계가 공개됐으면 409다 (data-model §10-1)")
    void publishModeConflicts() throws Exception {
        freeVolatilityTerm();
        calls.adminData(save(V2_ARTICLE, passingDraft(0, null, null, 0)), 200);
        assertThat(calls.adminError(publish(V2_ARTICLE, 1), 409).get("code").asText())
                .isEqualTo("LEARNING_STAGE_NOT_RELEASED");

        seeds.markStageReleased("returns-and-risk");
        assertThat(calls.adminError(publish(V1_ARTICLE, 0), 409).get("code").asText())
                .isEqualTo("LEARNING_FORMAT_OUTDATED");
        assertThat(count("learning_article_revision")).isZero();
    }

    @Test
    @DisplayName("공개된 단계의 V2 글은 v2 검사로 CURRENT 판이 되고, 예시가 없으면 대응표를 NOT_CHECKED로 남긴다 (T144)")
    void publishCurrentWithoutExample() throws Exception {
        freeVolatilityTerm();
        seeds.markStageReleased("returns-and-risk");
        calls.adminData(save(V2_ARTICLE, passingDraft(0, null, null, 0)), 200);

        JsonNode published = calls.adminData(publish(V2_ARTICLE, 1), 200);

        assertThat(published.get("edition").asText()).isEqualTo("CURRENT");
        assertThat(published.get("checkVersion").asText()).isEqualTo("learning-publish-checks-v2");
        assertThat(published.get("sectorMapVersion").asText()).isEqualTo("NOT_CHECKED");
        assertThat(published.get("renamedQuestions")).isEmpty();
        assertThat(published.get("transferredTerms")).isEmpty();
        assertThat(
                        jdbcTemplate.queryForMap(
                                "SELECT r.edition, r.sector_map_version FROM learning_article_revision r"))
                .containsEntry("edition", "CURRENT")
                .containsEntry("sector_map_version", "NOT_CHECKED");

        JsonNode article = publicData("/learning/articles/" + V2_ARTICLE);
        assertThat(article.get("edition").asText()).isEqualTo("CURRENT");
        assertThat(article.get("number").asText()).isEqualTo("2-1");
        assertThat(article.get("example").isNull()).isTrue();
        assertThat(article.get("readingMinutes").asInt()).isEqualTo(4);
        assertThat(article.get("quiz")).hasSize(2);
        // 도움말 문장은 그 판의 뜻 문장과 같은 값이다(SC-003).
        JsonNode term = publicData("/learning/terms/volatility");
        assertThat(term.get("sentence").asText()).isEqualTo("값이 오르내리는 정도다.");
        assertThat(term.get("articleId").asText()).isEqualTo(V2_ARTICLE);

        JsonNode listed = adminListItem(V2_ARTICLE);
        assertThat(listed.get("publishedEdition").asText()).isEqualTo("CURRENT");
        assertThat(listed.get("needsUpdate").isNull()).as("공개 판에 예시가 없으면 판정하지 않는다").isTrue();
    }

    @Test
    @DisplayName("V2 글에 예시가 있으면 v1과 같은 예시 검사가 걸리고, 통과하면 대응표 버전을 남긴다 (T144)")
    void exampleChecksApplyWhenPresent() throws Exception {
        freeVolatilityTerm();
        seeds.markStageReleased("returns-and-risk");
        seedFullSectorMap();
        ObjectNode lone = passingDraft(0, null, null, 0);
        lone.putObject("example")
                .putArray("sectors")
                .addObject()
                .put("code", "ENERGY")
                .put("weightTenths", 450);
        calls.adminData(save(V2_ARTICLE, lone), 200);

        assertThat(calls.adminError(publish(V2_ARTICLE, 1), 422).at("/details/failures"))
                .extracting(failure -> failure.get("check").asText())
                .containsExactly(
                        "EXAMPLE_SECTOR_COUNT", "EXAMPLE_SECTOR_IN_MAP", "EXAMPLE_WEIGHT_SUM");

        ObjectNode draft = passingDraft(1, null, null, 0);
        ArrayNode sectors = draft.putObject("example").putArray("sectors");
        sectors.addObject().put("code", "SEMICONDUCTOR").put("weightTenths", 500);
        sectors.addObject().put("code", "BATTERY").put("weightTenths", 450);
        calls.adminData(save(V2_ARTICLE, draft), 200);

        JsonNode failed = calls.adminError(publish(V2_ARTICLE, 2), 422);
        assertThat(failed.at("/details/failures"))
                .extracting(failure -> failure.get("check").asText())
                .containsExactly("EXAMPLE_WEIGHT_SUM");

        ((ObjectNode) sectors.get(1)).put("weightTenths", 500);
        draft.put("expectedDraftVersion", 2);
        calls.adminData(save(V2_ARTICLE, draft), 200);
        JsonNode published = calls.adminData(publish(V2_ARTICLE, 3), 200);

        assertThat(published.get("sectorMapVersion").asText()).isEqualTo(LearningSeeds.MAP_VERSION);
        assertThat(
                        publicData("/learning/articles/" + V2_ARTICLE)
                                .at("/example/available")
                                .asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("v2 검사에 걸리면 publish-check-failed-v2.json과 같은 422이고 아무것도 바뀌지 않는다 (quickstart 7-2)")
    void failingV2PublishMatchesExample() throws Exception {
        seeds.markStageReleased("returns-and-risk");
        calls.adminData(save(V2_ARTICLE, failingDraft(0)), 200);

        JsonNode error = calls.adminError(publish(V2_ARTICLE, 1), 422);

        assertThat(error).isEqualTo(example("publish-check-failed-v2.json"));
        assertThat(count("learning_article_revision")).isZero();
        assertThat(count("learning_publication_event")).isZero();
        assertThat(adminListItem(V2_ARTICLE).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("정답을 바꾼 문제만 새 식별자로 게시되고, 초안 번호는 그대로이며 옛 식별자 저장은 400이다 (quickstart 7-5)")
    void renamedQuestionsOnRepublish() throws Exception {
        freeVolatilityTerm();
        seeds.markStageReleased("returns-and-risk");
        JsonNode first = calls.adminData(save(V2_ARTICLE, passingDraft(0, null, null, 0)), 200);
        assertThat(first.at("/draft/quiz"))
                .extracting(question -> question.get("questionId").asText())
                .containsExactly("q1", "q4");
        calls.adminData(publish(V2_ARTICLE, 1), 200);

        // q1은 글자만, q4는 정답 보기를 바꾼다.
        ObjectNode changed =
                passingDraft(1, List.of("q1", "q4"), List.of("c2", "c3", "c5", "c6"), 1);
        ((ObjectNode) changed.at("/quiz/0")).put("prompt", "글자만 고친 질문");
        calls.adminData(save(V2_ARTICLE, changed), 200);
        JsonNode republished = calls.adminData(publish(V2_ARTICLE, 2), 200);

        assertThat(republished.get("renamedQuestions"))
                .isEqualTo(objectMapper.readTree("[{\"from\":\"q4\",\"to\":\"q7\"}]"));
        JsonNode reread = calls.adminData(get("/admin/learning/articles/" + V2_ARTICLE), 200);
        assertThat(reread.get("draftVersion").asInt()).isEqualTo(2);
        assertThat(reread.at("/draft/quiz"))
                .extracting(question -> question.get("questionId").asText())
                .containsExactly("q1", "q7");
        assertThat(publicData("/learning/articles/" + V2_ARTICLE).get("quiz"))
                .extracting(question -> question.get("questionId").asText())
                .containsExactly("q1", "q7");

        ObjectNode stale = passingDraft(2, List.of("q1", "q4"), List.of("c2", "c3", "c5", "c6"), 1);
        assertThat(fieldsOf(calls.adminError(save(V2_ARTICLE, stale), 400)))
                .containsExactly("quiz[1].questionId");
        ObjectNode fresh = passingDraft(2, List.of("q1", "q7"), List.of("c2", "c3", "c5", "c6"), 1);
        assertThat(calls.adminData(save(V2_ARTICLE, fresh), 200).get("draftVersion").asInt())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("게시 취소는 공개 배치를 비운다")
    void unpublishClearsPublishedPlacement() throws Exception {
        freeVolatilityTerm();
        seeds.markStageReleased("returns-and-risk");
        calls.adminData(save(V2_ARTICLE, passingDraft(0, null, null, 0)), 200);
        calls.adminData(publish(V2_ARTICLE, 1), 200);

        calls.adminData(post("/admin/learning/articles/" + V2_ARTICLE + "/unpublish"), 200);

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT published_stage_id, published_stage_position FROM learning_article"
                                + " WHERE article_key = ?",
                        V2_ARTICLE);
        assertThat(row.get("published_stage_id")).isNull();
        assertThat(row.get("published_stage_position")).isNull();
        assertThat(adminListItem(V2_ARTICLE).get("publishedEdition").isNull()).isTrue();
    }

    // --- §2 확인 필요 ---

    @Test
    @DisplayName("초안 배치를 옮겨도 공개 글은 그대로이고, 다시 게시하면 공개 배치가 새 배치로 바뀐다")
    void republishMovesPublishedPlacement() throws Exception {
        freeVolatilityTerm();
        seeds.markStageReleased("etf-market-basics");
        seeds.markStageReleased("returns-and-risk");
        calls.adminData(save(V2_ARTICLE, passingDraft(0, null, null, 0)), 200);
        calls.adminData(publish(V2_ARTICLE, 1), 200);
        ObjectNode moved = passingDraft(1, List.of("q1", "q4"), List.of("c2", "c3", "c5", "c6"), 0);
        moved.put("stageKey", "etf-market-basics");
        moved.put("stagePosition", 7);

        calls.adminData(save(V2_ARTICLE, moved), 200);

        JsonNode beforeRepublish = publicData("/learning/articles/" + V2_ARTICLE);
        assertThat(beforeRepublish.at("/stage/stageKey").asText()).isEqualTo("returns-and-risk");
        assertThat(beforeRepublish.get("number").asText()).isEqualTo("2-1");

        calls.adminData(publish(V2_ARTICLE, 2), 200);

        JsonNode afterRepublish = publicData("/learning/articles/" + V2_ARTICLE);
        assertThat(afterRepublish.at("/stage/stageKey").asText()).isEqualTo("etf-market-basics");
        assertThat(afterRepublish.get("number").asText()).isEqualTo("1-1");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT published_stage_position FROM learning_article"
                                        + " WHERE article_key = ?",
                                Integer.class,
                                V2_ARTICLE))
                .isEqualTo(7);
    }

    @Test
    @DisplayName(
            "검사를 통과하지 못한 V2 초안도 미리 보기로 그린다 — 정의 없는 용어 블록은 빈 문장, 정답이 하나가 아닌 문제는 correctChoiceId null (learning-admin-api §8)")
    void previewShowsUnfinishedV2Draft() throws Exception {
        ObjectNode body = v2Base(0);
        ArrayNode blocks = body.putArray("blocks");
        text(paragraph(blocks), "문단");
        blocks.addObject().put("type", "term").put("termId", "undefined-term");
        body.putArray("terms");
        ObjectNode question = question(body.putArray("quiz"), "정답이 없는 문제", "풀이");
        choice(question, null, "가", false);
        choice(question, null, "나", false);
        calls.adminData(save(V2_ARTICLE, body), 200);

        JsonNode preview =
                calls.adminData(get("/admin/learning/articles/" + V2_ARTICLE + "/preview"), 200);

        assertThat(preview.get("edition").asText()).isEqualTo("CURRENT");
        assertThat(preview.get("preview").asBoolean()).isTrue();
        assertThat(preview.get("minDwellSeconds").asInt()).isEqualTo(30);
        assertThat(preview.at("/blocks/1/termId").asText()).isEqualTo("undefined-term");
        assertThat(preview.at("/blocks/1/meaning").asText()).isEmpty();
        assertThat(preview.at("/quiz/0/correctChoiceId").isNull()).isTrue();
        assertThat(count("learning_article_revision")).isZero();
    }

    @Test
    @DisplayName(
            "V1 초안의 미리 보기는 9월 판 블록 모양이고, edition은 첫 단계 공개 전 PRE_RELEASE·뒤 PREVIOUS다 (learning-admin-api §8)")
    void previewOfV1Draft() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedDraftVersion", 0);
        body.put("title", "수익과 손실");
        body.put("question", "q");
        body.put("readingOrder", 8);
        ArrayNode blocks = body.putArray("blocks");
        blocks.addObject().put("type", "paragraph").put("text", "문단");
        blocks.addObject().put("type", "term").put("termId", "max-drawdown");
        body.putNull("example");
        body.putArray("sources");
        body.putArray("terms")
                .addObject()
                .put("termId", "max-drawdown")
                .put("label", "가장 크게 줄었던 폭")
                .put("sentence", "고점에서 가장 크게 줄었던 폭이다.");
        calls.adminData(save(V1_ARTICLE, body), 200);
        String url = "/admin/learning/articles/" + V1_ARTICLE + "/preview";

        JsonNode beforeRelease = calls.adminData(get(url), 200);

        assertThat(beforeRelease.get("edition").asText()).isEqualTo("PRE_RELEASE");
        assertThat(beforeRelease.get("minDwellSeconds").isNull()).isTrue();
        assertThat(beforeRelease.get("example").isNull()).isTrue();
        assertThat(beforeRelease.at("/blocks/0"))
                .isEqualTo(objectMapper.readTree("{\"type\":\"paragraph\",\"text\":\"문단\"}"));
        assertThat(beforeRelease.at("/blocks/1/sentence").asText())
                .isEqualTo("고점에서 가장 크게 줄었던 폭이다.");

        seeds.markStageReleased("etf-market-basics");

        assertThat(calls.adminData(get(url), 200).get("edition").asText()).isEqualTo("PREVIOUS");
    }

    @Test
    @DisplayName("없는 글의 미리 보기는 404 LEARNING_ARTICLE_NOT_FOUND다")
    void previewOfMissingArticle() throws Exception {
        JsonNode error =
                calls.adminError(get("/admin/learning/articles/no-such-article/preview"), 404);

        assertThat(error.get("code").asText()).isEqualTo("LEARNING_ARTICLE_NOT_FOUND");
    }

    @Test
    @DisplayName("공개 판의 제도 안내 기준 시점이 180일 넘게 지났으면 needsReview가 true다 (quickstart 7-6)")
    void needsReviewFollowsInstitutionNoteAge() throws Exception {
        seeds.markStageReleased("returns-and-risk");
        // 고정 시계로 경계(179·180·181일)를 보는 것은 InstitutionNoteReviewPeriodTest다. 여기서는 연결만 본다. 실행 중 자정을
        // 넘겨도 179·181일이 180·182일이 될 뿐이라 결과(false·true)가 같다.
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        ObjectNode overdue = (ObjectNode) example("article-current.json").deepCopy();
        setAsOf(overdue, today.minusDays(181));
        seeds.publishCurrentFromResponse(overdue);
        ObjectNode recent = (ObjectNode) example("article-current.json").deepCopy();
        recent.put("articleId", "same-return-different-feel");
        setAsOf(recent, today.minusDays(179));
        // 같은 용어를 두 글이 게시할 수 없으므로(색인 기본키) 둘째 글에서는 용어 블록을 뺀다.
        ArrayNode blocks = (ArrayNode) recent.get("blocks");
        for (int index = blocks.size() - 1; index >= 0; index--) {
            if ("term".equals(blocks.get(index).get("type").asText())) {
                blocks.remove(index);
            }
        }
        seeds.publishCurrentFromResponse(recent);

        assertThat(adminListItem(V2_ARTICLE).get("needsReview").asBoolean()).isTrue();
        assertThat(adminListItem("same-return-different-feel").get("needsReview").isBoolean())
                .isTrue();
        assertThat(adminListItem("same-return-different-feel").get("needsReview").asBoolean())
                .isFalse();
        assertThat(adminListItem(V1_ARTICLE).get("needsReview").isNull()).isTrue();
    }

    private static void setAsOf(ObjectNode article, LocalDate asOf) {
        for (JsonNode block : article.get("blocks")) {
            if ("institutionNote".equals(block.get("type").asText())) {
                ((ObjectNode) block).put("asOf", asOf.toString());
            }
        }
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
}
