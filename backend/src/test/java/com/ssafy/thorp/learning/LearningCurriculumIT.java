package com.ssafy.thorp.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thorp.learning.application.port.PublishCheckRulesPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.LearningStageRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.repository.ReleaseLogRepository;
import com.ssafy.thorp.learning.application.service.LearningQueryService;
import com.ssafy.thorp.learning.presentation.response.CurriculumResponse;
import com.ssafy.thorp.learning.support.LearningSeeds;
import com.ssafy.thorp.support.IntegrationTestSupport;
import com.ssafy.thorp.support.Requesters;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커리큘럼 조회(learning-public-api §1, quickstart 2-1·6-1·8-4·8-7·11-2 서버 부분, tasks T127).
 *
 * <p>{@code curriculum-pre-release.json}은 실제 요청과 JSON 동치다. {@code curriculum.json}의 {@code
 * notices}는 지금 시각에 따라 달라지므로, 공개 안내를 보는 경우는 시계를 고정한 {@link LearningQueryService}를 이 테스트 안에서 만들어 부른다.
 * 저장소·포트는 컨텍스트의 빈을 그대로 쓴다. 시계 빈을 바꾸면 컨텍스트를 새로 만들어야 한다({@link IntegrationTestSupport}).
 *
 * <p>판·단계 공개·변경 기록은 {@link LearningSeeds}로 SQL로 심는다. 이 테스트는 "공개 상태 → 응답"만 본다. 단계 공개 유스케이스는 {@code
 * LearningStageReleaseIT}가 본다.
 */
class LearningCurriculumIT extends IntegrationTestSupport {

    private static final Path EXAMPLES = Path.of("..", "docs", "specs", "learning-api", "examples");

    /** {@code curriculum.json}의 시각. 2단계 공개 다음 날이라 2단계 공개만 안내에 든다. */
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

    private static final Instant STAGE_1_RELEASED = Instant.parse("2026-11-12T01:00:00Z");
    private static final Instant STAGE_2_RELEASED = Instant.parse("2026-11-19T01:00:00Z");

    /** 1단계 공개 뒤 그 단계에 더한 글({@code etf-fees})의 첫 게시 시각. */
    private static final Instant ETF_FEES_ADDED = Instant.parse("2026-11-15T01:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LearningArticleRepository articleRepository;
    @Autowired private PublishedTermRepository publishedTermRepository;
    @Autowired private LearningStageRepository stageRepository;
    @Autowired private ReleaseLogRepository releaseLogRepository;
    @Autowired private SectorMapPort sectorMapPort;
    @Autowired private PublishCheckRulesPort rulesPort;
    @Autowired private PlatformTransactionManager transactionManager;

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

    private JsonNode requestCurriculum() throws Exception {
        String body =
                mockMvc.perform(get("/learning/curriculum"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data");
    }

    /** 시계를 고정한 조회. 서비스의 트랜잭션 설정과 같이 읽기 전용 한 트랜잭션에서 부른다. */
    private JsonNode curriculumAt(Instant now) {
        LearningQueryService service =
                new LearningQueryService(
                        articleRepository,
                        publishedTermRepository,
                        stageRepository,
                        releaseLogRepository,
                        sectorMapPort,
                        rulesPort,
                        Clock.fixed(now, ZoneOffset.UTC));
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return objectMapper.valueToTree(
                readOnly.execute(status -> CurriculumResponse.from(service.curriculum())));
    }

    /** {@code curriculum-pre-release.json}의 {@code preReleaseArticles} 두 글을 LEGACY 판으로 심는다. */
    private void seedPreReleaseArticles() throws Exception {
        for (JsonNode article : example("curriculum-pre-release.json").get("preReleaseArticles")) {
            seeds.publishMinimal(
                    article.get("articleId").asText(),
                    article.get("title").asText(),
                    article.get("question").asText(),
                    article.get("readingOrder").asInt());
        }
    }

    /**
     * {@code curriculum.json}의 상태. 1·2단계가 공개됐고 그 여덟 글이 CURRENT 판이다. 1단계의 {@code etf-fees}는 단계 공개 뒤에
     * 더한 글이다({@code ARTICLE_ADDED}). 3~5단계의 기존 글은 LEGACY 판이 게시 중이다. 6단계와 3~5단계의 새 글은 초안뿐이다.
     */
    private void seedTwoStagesReleased() {
        seeds.markStageReleased("etf-market-basics", STAGE_1_RELEASED);
        seeds.markStageReleased("returns-and-risk", STAGE_2_RELEASED);
        seeds.publishCurrent(
                "sectors-and-etf",
                "섹터와 ETF",
                "섹터와 대표 ETF는 무엇이 같고 무엇이 다른가?",
                5,
                Map.of("sector", List.of(), "representative-etf", List.of()),
                true);
        seeds.publishCurrent(
                "etf-tracking-difference",
                "ETF 가격은 왜 지수와 똑같이 움직이지 않을까?",
                "ETF 가격과 지수의 차이는 어디서 생길까?",
                4,
                Map.of("tracking-difference", List.of("추적 차이")),
                false);
        seeds.publishCurrent(
                "etf-distributions",
                "분배금을 받으면 돈이 늘어난 걸까?",
                "분배금을 받은 뒤 가격은 어떻게 달라질까?",
                5,
                Map.of("distribution", List.of()),
                false);
        seeds.publishCurrent(
                "etf-fees", "ETF 보수는 어디로 빠져나갈까?", "보수는 수익률에 어떻게 반영될까?", 4, Map.of(), false);
        seeds.publishCurrent(
                "returns-and-losses",
                "수익과 손실",
                "가장 크게 줄었던 폭은 왜 시작 금액 대비 손실과 다른가?",
                6,
                Map.of("net-total-return", List.of(), "max-drawdown", List.of("MDD")),
                true);
        seeds.publishCurrent(
                "same-return-different-feel",
                "같은 수익률인데 왜 느낌이 다를까?",
                "끝 수익률이 같아도 지나온 길은 다를 수 있을까?",
                4,
                Map.of("path-dependence", List.of()),
                false);
        seeds.publishCurrent(
                "recovery-period",
                "떨어진 뒤 얼마나 걸려 돌아왔을까?",
                "하락 뒤 회복에 걸린 기간은 어떻게 셀까?",
                5,
                Map.of("recovery-period", List.of()),
                false);
        seeds.publishCurrent(
                "volatility-is-not-loss",
                "변동성은 손실일까?",
                "값이 크게 오르내렸다는 것은 돈을 잃었다는 뜻일까?",
                5,
                Map.of("volatility", List.of("volatility")),
                false);
        seeds.publishLegacyWithBody(
                "weights-and-rebalancing", "비중과 리밸런싱", "비중을 정해 두면 왜 다시 맞춰야 할까?", 2, 1000);
        seeds.publishMinimal(
                "inverse-volatility", "변동성에 따라 나누기", "덜 흔들린 섹터에 더 많이 담는다는 것은 무슨 뜻일까?", 3);
        seeds.publishLegacyWithBody(
                "sector-momentum", "섹터 모멘텀", "최근 많이 오른 섹터를 고르는 규칙은 어떻게 움직일까?", 4, 1500);
        seeds.publishMinimal("minimum-variance", "최소분산", "함께 덜 흔들리는 조합은 어떻게 찾을까?", 5);
        seeds.publishLegacyWithBody("backtest", "백테스트", "과거 자료로 규칙을 돌려 본다는 것은 무엇일까?", 6, 1000);
        seeds.publishMinimal("transaction-cost", "거래비용", "사고팔 때마다 빠지는 비용은 결과를 얼마나 바꿀까?", 7);
        seeds.publishLegacyWithBody(
                "past-is-not-future",
                "과거가 미래를 보장하지 않는 이유",
                "과거에 좋았던 결과가 앞으로도 좋으리라 말할 수 없는 이유는?",
                9,
                1000);
        seeds.logStageReleased("etf-market-basics", 2, List.of("섹터와 ETF"), STAGE_1_RELEASED);
        seeds.logArticleAdded(
                "etf-market-basics", "etf-fees", "ETF 보수는 어디로 빠져나갈까?", ETF_FEES_ADDED);
        seeds.logStageReleased("returns-and-risk", 3, List.of("수익과 손실"), STAGE_2_RELEASED);
    }

    @Test
    @DisplayName(
            "첫 단계 공개 전에는 curriculum-pre-release.json과 같다 — 공개 중인 글만 판의 읽는 순서로, 모든 단계 준비 중 (quickstart 6-1)")
    void preReleaseMatchesExample() throws Exception {
        seedPreReleaseArticles();

        JsonNode curriculum = requestCurriculum();

        assertThat(curriculum).isEqualTo(example("curriculum-pre-release.json"));
    }

    @Test
    @DisplayName("첫 단계 공개 전 글은 판의 readingOrder, articleId 순이고 초안 컬럼의 순서를 따르지 않는다")
    void preReleaseArticlesAreOrderedByRevisionReadingOrderThenArticleId() throws Exception {
        // 넣는 차례를 기대하는 차례와 다르게 둔다. 초안 reading_order는 backtest 6, transaction-cost 7,
        // sectors-and-etf 1이지만 판에는 1·1·2를 준다 — 초안 컬럼으로 정렬하면 순서가 달라진다.
        seeds.publishMinimal("transaction-cost", "거래비용", "q", 2);
        seeds.publishMinimal("sectors-and-etf", "섹터와 ETF", "q", 1);
        seeds.publishMinimal("backtest", "백테스트", "q", 1);

        assertThat(requestCurriculum().get("preReleaseArticles"))
                .extracting(item -> item.get("articleId").asText())
                .containsExactly("backtest", "sectors-and-etf", "transaction-cost");
    }

    @Test
    @DisplayName(
            "1·2단계가 공개된 때는 curriculum.json과 같다 — 공개된 단계의 번호·읽기 분·용어 수, 준비 중 단계의 이전 판 글, 7일 안의 안내 (quickstart 8-4, 11-2)")
    void releasedMatchesExample() throws Exception {
        seedTwoStagesReleased();

        assertThat(curriculumAt(NOW)).isEqualTo(example("curriculum.json"));

        // 실제 요청도 공개 안내(지금 시각에 따른다) 밖은 같다.
        ObjectNode requested = (ObjectNode) requestCurriculum();
        ObjectNode expected = (ObjectNode) example("curriculum.json");
        requested.remove("notices");
        expected.remove("notices");
        assertThat(requested).isEqualTo(expected);
    }

    @Test
    @DisplayName("커리큘럼은 판 본문을 읽지 않는다 — 공개된 단계의 글 본문이 깨져 있어도 응답은 그대로다")
    void brokenBodyDoesNotBreakCurriculum() throws Exception {
        seedTwoStagesReleased();
        jdbcTemplate.update(
                "UPDATE learning_article_revision"
                        + " SET snapshot = jsonb_set(snapshot, '{bodyBlocks}', '[{\"type\":\"broken\"}]')"
                        + " WHERE id = (SELECT published_revision_id FROM learning_article"
                        + " WHERE article_key = 'returns-and-losses')");

        assertThat(curriculumAt(NOW)).isEqualTo(example("curriculum.json"));
    }

    @Test
    @DisplayName("공개 안내는 지금부터 7일 안의 줄이다 — 7일 직전은 싣고 7일 직후는 싣지 않으며, 변경 기록에는 늘 있다 (quickstart 11-2)")
    void noticesCoverSevenDays() {
        seedTwoStagesReleased();
        Instant sevenDaysAfter = STAGE_2_RELEASED.plus(Duration.ofDays(7));

        JsonNode justBefore = curriculumAt(sevenDaysAfter.minusSeconds(1));
        JsonNode exactly = curriculumAt(sevenDaysAfter);
        JsonNode justAfter = curriculumAt(sevenDaysAfter.plusSeconds(1));

        assertThat(justBefore.get("notices"))
                .extracting(line -> line.get("entryKey").asText())
                .containsExactly("stage:returns-and-risk");
        assertThat(exactly.get("notices"))
                .as("7일째 같은 시각까지는 안내에 든다")
                .extracting(line -> line.get("entryKey").asText())
                .containsExactly("stage:returns-and-risk");
        assertThat(justAfter.get("notices")).isEmpty();
        assertThat(justAfter.get("changeLog"))
                .extracting(line -> line.get("entryKey").asText())
                .containsExactly(
                        "stage:returns-and-risk", "article:etf-fees", "stage:etf-market-basics");
    }

    @Test
    @DisplayName(
            "공개된 단계에 더한 글의 ARTICLE_ADDED 줄은 그때 제목·새 글 1편으로 실리고, 같은 시각이면 나중에 쓴 줄이 먼저다 (quickstart 8-6)")
    void articleAddedLineCarriesTitle() {
        seedTwoStagesReleased();
        // 2단계 공개와 같은 시각에 쓴 글 추가 줄. 같은 시각이면 나중에 더한 줄이 먼저 온다.
        seeds.logArticleAdded(
                "returns-and-risk", "recovery-period", "떨어진 뒤 얼마나 걸려 돌아왔을까?", STAGE_2_RELEASED);

        JsonNode curriculum = curriculumAt(NOW);

        assertThat(curriculum.get("changeLog"))
                .extracting(line -> line.get("entryKey").asText())
                .containsExactly(
                        "article:recovery-period",
                        "stage:returns-and-risk",
                        "article:etf-fees",
                        "stage:etf-market-basics");
        JsonNode line = curriculum.at("/changeLog/2");
        assertThat(line.get("kind").asText()).isEqualTo("ARTICLE_ADDED");
        assertThat(line.get("stageNumber").asInt()).isEqualTo(1);
        assertThat(line.get("stageName").asText()).isEqualTo("ETF와 국내 시장 기초");
        assertThat(line.get("newCount").asInt()).isEqualTo(1);
        assertThat(line.get("rewrittenCount").asInt()).isZero();
        assertThat(line.get("rewrittenTitles")).isEmpty();
        assertThat(line.get("articleTitle").asText()).isEqualTo("ETF 보수는 어디로 빠져나갈까?");
        assertThat(curriculum.at("/changeLog/1/articleTitle").isNull())
                .as("단계 공개 줄은 글 제목이 null이다")
                .isTrue();
    }

    @Test
    @DisplayName("공개된 단계의 글을 모두 게시 취소하면 REPREPARING이고 공개일·공개된 단계 수는 그대로다 (quickstart 8-7)")
    void unpublishingEveryArticleRepreparesStage() throws Exception {
        seedTwoStagesReleased();
        for (String articleId :
                List.of(
                        "returns-and-losses",
                        "same-return-different-feel",
                        "recovery-period",
                        "volatility-is-not-loss")) {
            mockMvc.perform(
                            post("/admin/learning/articles/" + articleId + "/unpublish")
                                    .with(Requesters.as(7))
                                    .with(csrf()))
                    .andExpect(status().isOk());
        }

        JsonNode curriculum = curriculumAt(NOW);

        JsonNode second = curriculum.at("/stages/1");
        assertThat(second.get("status").asText()).isEqualTo("REPREPARING");
        assertThat(second.get("articles")).isEmpty();
        assertThat(second.get("previousEditionArticles")).isEmpty();
        assertThat(second.get("releasedAt").asText()).isEqualTo(STAGE_2_RELEASED.toString());
        assertThat(curriculum.get("releasedStageCount").asInt()).isEqualTo(2);
        assertThat(curriculum.at("/stages/0/status").asText()).isEqualTo("OPEN");
        assertThat(curriculum.get("changeLog")).hasSize(3);
    }

    @Test
    @DisplayName("공개 전 단계의 새 글은 초안을 채워 두어도 제목·식별자가 응답 어디에도 없다 (SC-017)")
    void unreleasedNewArticlesStayHidden() throws Exception {
        seedTwoStagesReleased();
        // 3단계 새 글에 초안 제목을 고쳐 둔다. 공개 전 단계라 게시되지 않는다.
        jdbcTemplate.update(
                "UPDATE learning_article SET title = '공개 전 새 글 초안 제목', draft_version = 4"
                        + " WHERE article_key = 'diversification-and-correlation'");

        String released = objectMapper.writeValueAsString(curriculumAt(NOW));
        String preRelease;
        seeds.reset();
        seedPreReleaseArticles();
        preRelease = objectMapper.writeValueAsString(requestCurriculum());

        for (String body : List.of(released, preRelease)) {
            assertThat(body)
                    .doesNotContain("공개 전 새 글 초안 제목")
                    .doesNotContain("diversification-and-correlation")
                    .doesNotContain("why-lookback-period")
                    .doesNotContain("나누기만 하면 분산일까?")
                    .doesNotContain("시장 흐름의 7개 분야는 무엇을 보여줄까?");
        }
    }

    @Test
    @DisplayName("게시된 글이 하나도 없어도 200이고 여섯 단계가 모두 준비 중이다 (quickstart 2-1)")
    void emptyCurriculumIsOk() throws Exception {
        JsonNode curriculum = requestCurriculum();

        // 공개 전 목록은 null이 아니라 빈 배열이다. 화면은 배열인지로 첫 단계 공개 전을 가른다(계약 §1).
        assertThat(curriculum.get("preReleaseArticles").isArray()).isTrue();

        assertThat(curriculum.get("stages")).hasSize(6);
        assertThat(curriculum.get("stages"))
                .allSatisfy(
                        stage -> assertThat(stage.get("status").asText()).isEqualTo("PREPARING"));
        assertThat(curriculum.get("preReleaseArticles")).isEmpty();
        assertThat(curriculum.get("changeLog")).isEmpty();
    }
}
