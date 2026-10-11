package com.ssafy.thorp.learning.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import com.ssafy.thorp.backtest.infrastructure.messaging.MessageJsonPersistenceConfig;
import com.ssafy.thorp.learning.application.result.CurrentArticleEntry;
import com.ssafy.thorp.learning.application.result.LearningArticleSummaryResult;
import com.ssafy.thorp.learning.application.result.LearningTermResult;
import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramBlock.Series;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramKind;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.InstitutionNoteBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.KeySummaryBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ListBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.MisconceptionBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TableBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.ArticleSnapshot;
import com.ssafy.thorp.learning.domain.model.ArticleSource;
import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.LearningArticle.DraftContent;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.Placement;
import com.ssafy.thorp.learning.domain.model.PublicationEvent;
import com.ssafy.thorp.learning.domain.model.PublicationEvent.PublicationAction;
import com.ssafy.thorp.learning.domain.model.QuizChoice;
import com.ssafy.thorp.learning.domain.model.QuizQuestion;
import com.ssafy.thorp.learning.domain.model.ReadingEstimate;
import com.ssafy.thorp.learning.domain.model.RichText;
import com.ssafy.thorp.learning.domain.model.RichText.SourceRun;
import com.ssafy.thorp.learning.domain.model.RichText.TermMentionRun;
import com.ssafy.thorp.learning.domain.model.RichText.TextRun;
import com.ssafy.thorp.learning.domain.model.SourceRef;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation.SectorWeight;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import com.ssafy.thorp.support.RepositoryTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 저장소 구현의 쓰기 경로를 실제 PostgreSQL에서 확인한다(T011). JSONB 왕복, 같은 {@code term_key} 재삽입, 잠근 뒤 {@code
 * refresh}, 판·기록·색인 추가와 조회.
 *
 * <p>JSON 컬럼은 운영과 같이 메시지 ObjectMapper로 직렬화되도록 {@link MessageJsonPersistenceConfig}를 함께 올린다. 빠지면
 * Hibernate 기본 매퍼로 저장돼 null 키 생략 규칙이 운영과 달라진다.
 *
 * <p>{@code @DataJpaTest}가 테스트마다 트랜잭션을 되돌리므로 초기 자리는 그대로 남는다. 읽기 전에 {@code flush}·{@code clear}로 1차
 * 캐시를 비워, 매핑을 거친 값을 DB에서 다시 읽는지 본다.
 */
class LearningRepositoryTest extends RepositoryTestSupport {

    private static final ArticleId ARTICLE = new ArticleId("returns-and-losses");
    private static final TermId MAX_DRAWDOWN = new TermId("max-drawdown");
    private static final TermId NET_TOTAL_RETURN = new TermId("net-total-return");

    @Autowired private LearningArticleRepositoryImpl articleRepository;
    @Autowired private LearningTermRepositoryImpl termRepository;
    @Autowired private ArticleRevisionRepositoryImpl revisionRepository;
    @Autowired private PublicationEventRepositoryImpl eventRepository;
    @Autowired private PublishedTermRepositoryImpl publishedTermRepository;
    @Autowired private TestEntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final List<ArticleBlock> BLOCKS =
            List.of(
                    new HeadingBlock("소제목"),
                    new ParagraphBlock("문단"),
                    new TermBlock(MAX_DRAWDOWN),
                    new HypotheticalBlock("100만 원이 80만 원이 되면 20% 줄어든 것이다"));

    private static final ExampleAllocation EXAMPLE =
            new ExampleAllocation(
                    List.of(
                            new SectorWeight("SEMICONDUCTOR", 500),
                            new SectorWeight("BATTERY", 500)));

    private static final List<ArticleSource> SOURCES =
            List.of(
                    new ArticleSource("주소 없는 출처", null),
                    new ArticleSource("출처", "https://example.com"));

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private LearningArticle load() {
        return articleRepository.findByArticleKey(ARTICLE).orElseThrow();
    }

    private LearningArticle saveDraftWithContent() {
        LearningArticle draft = load();
        LearningArticle saved =
                articleRepository.save(
                        draft.saveDraft(
                                draft.draftVersion(),
                                new DraftContent(
                                        "수익과 손실",
                                        "질문",
                                        8,
                                        ArticleFormat.V1,
                                        draft.placement(),
                                        BLOCKS,
                                        EXAMPLE,
                                        SOURCES,
                                        draft.terms(),
                                        List.of()),
                                false));
        flushAndClear();
        return saved;
    }

    @Test
    @DisplayName("초안 저장이 블록·예시·출처를 JSONB로 왕복하고 draftVersion을 올린다")
    void draftContentRoundTrips() {
        int before = load().draftVersion();
        saveDraftWithContent();

        LearningArticle reloaded = load();

        assertThat(reloaded.draftVersion()).isEqualTo(before + 1);
        assertThat(reloaded.question()).isEqualTo("질문");
        assertThat(reloaded.blocks()).isEqualTo(BLOCKS);
        assertThat(reloaded.example()).isEqualTo(EXAMPLE);
        assertThat(reloaded.sources()).isEqualTo(SOURCES);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT jsonb_exists(body_blocks -> 0, 'termId') FROM learning_article WHERE article_key = ?",
                                Boolean.class,
                                ARTICLE.value()))
                .as("null 필드는 키가 생략된다(메시지 ObjectMapper)")
                .isFalse();
    }

    @Test
    @DisplayName("초안 용어를 통째로 바꿀 때 남는 termId를 다시 넣어도 UNIQUE에 걸리지 않고, 빠진 용어는 지워진다")
    void replaceTermsReinsertsSameKey() {
        termRepository.replaceForArticle(
                ARTICLE,
                List.of(
                        new LearningTerm(MAX_DRAWDOWN, "가장 크게 줄었던 폭", "새 문장"),
                        new LearningTerm(new TermId("drawdown-period"), "줄어든 기간", "")));
        flushAndClear();

        assertThat(load().terms())
                .extracting(term -> term.termId().value(), LearningTerm::sentence)
                .containsExactly(tuple("max-drawdown", "새 문장"), tuple("drawdown-period", ""));
        assertThat(termRepository.findOwnerArticleKeys(List.of(NET_TOTAL_RETURN, MAX_DRAWDOWN)))
                .containsExactly(entry(MAX_DRAWDOWN, ARTICLE));
    }

    @Test
    @DisplayName("잠금 조회는 1차 캐시가 아니라 잠근 시점의 행을 돌려준다")
    void lockReadRefreshesFirstLevelCache() {
        load();
        entityManager.flush();
        // 영속성 컨텍스트를 우회해 바꾼다. 다른 트랜잭션이 그 사이 커밋한 것과 같은 상황이다.
        jdbcTemplate.update(
                "UPDATE learning_article SET title = '다른 곳에서 바뀐 제목' WHERE article_key = ?",
                ARTICLE.value());

        assertThat(articleRepository.findByArticleKeyForUpdate(ARTICLE).orElseThrow().title())
                .isEqualTo("다른 곳에서 바뀐 제목");
    }

    @Test
    @DisplayName("판을 추가하고 게시하면 공개 판·목록 항목·게시 용어 색인·기록이 함께 읽힌다")
    void publishFlowRoundTrips() {
        LearningArticle draft = saveDraftWithContent();
        List<LearningTerm> terms = List.of(new LearningTerm(MAX_DRAWDOWN, "가장 크게 줄었던 폭", "판의 문장"));
        ArticleSnapshot snapshot =
                ArticleSnapshot.legacy(
                        ARTICLE,
                        "수익과 손실",
                        "질문",
                        8,
                        BLOCKS,
                        EXAMPLE,
                        SOURCES,
                        terms,
                        draft.draftVersion());
        Instant publishedAt = Instant.parse("2026-09-20T01:00:00Z");

        ArticleRevision saved =
                revisionRepository.save(
                        new ArticleRevision(
                                null,
                                1,
                                Edition.LEGACY,
                                snapshot,
                                "learning-publish-checks-v1",
                                "SEM-1",
                                7L,
                                publishedAt));
        articleRepository.save(load().publish(draft.draftVersion(), saved.id()));
        publishedTermRepository.replaceForArticle(ARTICLE, saved.id(), terms, ArticleFormat.V1);
        eventRepository.append(
                new PublicationEvent(
                        null, ARTICLE, PublicationAction.PUBLISH, saved.id(), 7L, publishedAt));
        flushAndClear();

        assertThat(revisionRepository.findById(saved.id())).contains(saved);
        assertThat(revisionRepository.findByArticleId(ARTICLE)).containsExactly(saved);
        assertThat(articleRepository.findPublishedRevision(ARTICLE)).contains(saved);
        assertThat(load().publishedPlacement()).isEqualTo(load().placement());
        assertThat(articleRepository.findPublished(ARTICLE))
                .hasValueSatisfying(
                        view -> {
                            assertThat(view.revision()).isEqualTo(saved);
                            assertThat(view.placement())
                                    .isEqualTo(new Placement(new StageKey("returns-and-risk"), 1));
                        });
        assertThat(articleRepository.findPublishedOrdered())
                .containsExactly(
                        new LearningArticleSummaryResult("returns-and-losses", "수익과 손실", "질문", 8));
        assertThat(publishedTermRepository.findPublished(MAX_DRAWDOWN))
                .contains(
                        new LearningTermResult(
                                "max-drawdown",
                                "가장 크게 줄었던 폭",
                                "판의 문장",
                                "returns-and-losses",
                                "수익과 손실"));
        assertThat(eventRepository.findLastByArticleId(ARTICLE))
                .hasValueSatisfying(
                        event -> {
                            assertThat(event.action()).isEqualTo(PublicationAction.PUBLISH);
                            assertThat(event.revisionId()).isEqualTo(saved.id());
                            assertThat(event.actorUserId()).isEqualTo(7L);
                        });
        // 관리 목록과 게시가 쓰는 묶음·단건 조회도 같은 값을 돌려준다.
        assertThat(articleRepository.findPublishedRevisions())
                .containsExactly(Map.entry(ARTICLE, saved));
        assertThat(eventRepository.findLastForEachArticle())
                .containsOnlyKeys(ARTICLE)
                .extractingByKey(ARTICLE)
                .extracting(PublicationEvent::revisionId)
                .isEqualTo(saved.id());
        assertThat(revisionRepository.findLatestByArticleId(ARTICLE)).contains(saved);
        assertThat(revisionRepository.existsByArticleIdAndEdition(ARTICLE, Edition.LEGACY))
                .isTrue();
        assertThat(revisionRepository.existsByArticleIdAndEdition(ARTICLE, Edition.CURRENT))
                .isFalse();
        assertThat(revisionRepository.findRevisionNos(List.of(saved.id(), -1L)))
                .containsExactly(Map.entry(saved.id(), 1));
        assertThat(articleRepository.findAllOrdered())
                .hasSize(27)
                .filteredOn(article -> article.articleId().equals(ARTICLE))
                .singleElement()
                .satisfies(article -> assertThat(article.terms()).isEqualTo(load().terms()));

        // 같은 판으로 색인을 다시 써도 같은 term_key가 UNIQUE에 걸리지 않고, 지우면 공개에서 빠진다.
        publishedTermRepository.replaceForArticle(ARTICLE, saved.id(), terms, ArticleFormat.V1);
        flushAndClear();
        assertThat(publishedTermRepository.findPublished(MAX_DRAWDOWN)).isPresent();
        publishedTermRepository.deleteForArticle(ARTICLE);
        flushAndClear();
        assertThat(publishedTermRepository.findPublished(MAX_DRAWDOWN)).isEmpty();
    }

    @Test
    @DisplayName("이미 저장된 판은 다시 저장할 수 없다")
    void revisionsAreAppendOnly() {
        ArticleSnapshot snapshot =
                ArticleSnapshot.legacy(
                        ARTICLE, "t", "q", 1, List.of(), EXAMPLE, List.of(), List.of(), 0);
        ArticleRevision existing =
                new ArticleRevision(99L, 1, Edition.LEGACY, snapshot, "v", "m", 1L, Instant.EPOCH);

        assertThatThrownBy(() -> revisionRepository.save(existing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 온전한 판 JSON. 테스트마다 필수 키 하나를 빼고 넣는다. */
    private static final String COMPLETE_SNAPSHOT =
            """
            {"articleId":"returns-and-losses","title":"수익과 손실","question":"질문","readingOrder":8,
             "bodyBlocks":[{"type":"paragraph","text":"문단"}],
             "example":{"sectors":[{"code":"SEMICONDUCTOR","weightTenths":500},{"code":"BATTERY","weightTenths":500}]},
             "sources":[],"terms":[],"draftVersion":3}
            """;

    /**
     * 판 JSON을 SQL로 직접 넣고 글을 그 판으로 게시한다. 저장 경로는 필수 키를 늘 쓰므로 빠진 판은 이렇게만 만들 수 있다. 공개 배치는 공개 판과 함께 있어야
     * 하므로(테이블 CHECK) 초안 배치로 함께 채운다.
     */
    private long insertPublishedRevision(String snapshotJson) {
        long articleId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM learning_article WHERE article_key = ?",
                        Long.class,
                        ARTICLE.value());
        // V2 판(format 키가 V2)은 CURRENT, 그 밖은 LEGACY다. 판 번호는 그 글의 다음 번호다.
        long revisionId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO learning_article_revision (article_id, revision_no, snapshot,"
                                + " check_version, sector_map_version, published_by_user_id,"
                                + " published_at, edition)"
                                + " SELECT ?, COALESCE(max(revision_no), 0) + 1, CAST(? AS JSONB),"
                                + " 'v', 'm', 1, now(),"
                                + " CASE WHEN CAST(? AS JSONB) ->> 'format' = 'V2' THEN 'CURRENT'"
                                + " ELSE 'LEGACY' END"
                                + " FROM learning_article_revision WHERE article_id = ?"
                                + " RETURNING id",
                        Long.class,
                        articleId,
                        snapshotJson,
                        snapshotJson,
                        articleId);
        jdbcTemplate.update(
                "UPDATE learning_article SET status = 'PUBLISHED', published_revision_id = ?,"
                        + " published_stage_id = stage_id, published_stage_position = stage_position"
                        + " WHERE id = ?",
                revisionId,
                articleId);
        return revisionId;
    }

    @Test
    @DisplayName("온전한 판 JSON은 SQL로 넣어도 그대로 읽힌다 (아래 실패 테스트의 대조군)")
    void completeRawSnapshotIsReadable() {
        long id = insertPublishedRevision(COMPLETE_SNAPSHOT);

        ArticleRevision revision = revisionRepository.findById(id).orElseThrow();

        assertThat(revision.snapshot().readingOrder()).isEqualTo(8);
        assertThat(revision.snapshot().draftVersion()).isEqualTo(3);
        assertThat(articleRepository.findPublishedOrdered()).hasSize(1);
    }

    @Test
    @DisplayName("판 JSON에 readingOrder가 없으면 0으로 채우지 않고 목록·단건 모두 실패한다 (원칙 VI)")
    void missingScalarKeysFailInsteadOfZero() {
        long noOrder =
                insertPublishedRevision(COMPLETE_SNAPSHOT.replace("\"readingOrder\":8,", ""));
        assertThatThrownBy(() -> revisionRepository.findById(noOrder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readingOrder");
        assertThatThrownBy(() -> articleRepository.findPublishedOrdered())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readingOrder");
    }

    @Test
    @DisplayName("판 JSON에 draftVersion이 없으면 실패한다")
    void missingDraftVersionFails() {
        long id = insertPublishedRevision(COMPLETE_SNAPSHOT.replace(",\"draftVersion\":3", ""));

        assertThatThrownBy(() -> revisionRepository.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("draftVersion");
    }

    @Test
    @DisplayName("예시 섹터에 weightTenths가 없으면 비중 0으로 채우지 않고 실패한다")
    void missingWeightFails() {
        long id =
                insertPublishedRevision(
                        COMPLETE_SNAPSHOT.replace(
                                "{\"code\":\"BATTERY\",\"weightTenths\":500}",
                                "{\"code\":\"BATTERY\"}"));

        assertThatThrownBy(() -> revisionRepository.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("weightTenths");
    }

    @Test
    @DisplayName("판 JSON에 bodyBlocks가 없으면 빈 본문으로 채우지 않고 실패한다")
    void missingBodyBlocksFails() {
        long id =
                insertPublishedRevision(
                        COMPLETE_SNAPSHOT.replace(
                                "\"bodyBlocks\":[{\"type\":\"paragraph\",\"text\":\"문단\"}],", ""));

        assertThatThrownBy(() -> revisionRepository.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bodyBlocks");
    }

    @Test
    @DisplayName("판 JSON에 sources가 없으면 빈 목록으로 채우지 않고 실패한다")
    void missingListKeysFail() {
        long noSources = insertPublishedRevision(COMPLETE_SNAPSHOT.replace("\"sources\":[],", ""));
        assertThatThrownBy(() -> revisionRepository.findById(noSources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sources");
    }

    @Test
    @DisplayName("판 JSON에 title이 없으면 용어 조회도 articleTitle을 null로 내보내지 않고 실패한다")
    void missingTitleFailsTermLookup() {
        long revisionId =
                insertPublishedRevision(COMPLETE_SNAPSHOT.replace("\"title\":\"수익과 손실\",", ""));
        jdbcTemplate.update(
                "INSERT INTO learning_published_term (term_key, article_id, revision_id, label,"
                        + " sentence) SELECT 'max-drawdown', id, ?, '가장 크게 줄었던 폭', '문장'"
                        + " FROM learning_article WHERE article_key = ?",
                revisionId,
                ARTICLE.value());

        assertThatThrownBy(() -> publishedTermRepository.findPublished(MAX_DRAWDOWN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("title");
    }

    @Test
    @DisplayName("판 JSON에 terms가 없으면 실패한다")
    void missingTermsFails() {
        long id = insertPublishedRevision(COMPLETE_SNAPSHOT.replace("\"terms\":[],", ""));

        assertThatThrownBy(() -> revisionRepository.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terms");
    }

    @Test
    @DisplayName("판 JSON의 articleId 형식이 틀리면 입력 오류(IllegalArgumentException)가 아니라 저장 데이터 오류로 실패한다")
    void invalidStoredArticleIdIsDataError() {
        long id =
                insertPublishedRevision(
                        COMPLETE_SNAPSHOT.replace(
                                "\"articleId\":\"returns-and-losses\"",
                                "\"articleId\":\"Bad_Key\""));

        assertThatThrownBy(() -> revisionRepository.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("articleId")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("판 JSON의 블록 길이가 한도를 넘으면 저장 데이터 오류로 실패한다")
    void oversizedStoredBlockIsDataError() {
        long id =
                insertPublishedRevision(
                        COMPLETE_SNAPSHOT.replace(
                                "{\"type\":\"paragraph\",\"text\":\"문단\"}",
                                "{\"type\":\"heading\",\"text\":\"" + "가".repeat(101) + "\"}"));

        assertThatThrownBy(() -> revisionRepository.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bodyBlocks")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("판 JSON의 비중이 범위를 넘으면 저장 데이터 오류로 실패한다")
    void outOfRangeStoredWeightIsDataError() {
        long id =
                insertPublishedRevision(
                        COMPLETE_SNAPSHOT.replace(
                                "{\"code\":\"BATTERY\",\"weightTenths\":500}",
                                "{\"code\":\"BATTERY\",\"weightTenths\":1500}"));

        assertThatThrownBy(() -> revisionRepository.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("example")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    // --- 2판: V2 초안·판의 JSON 왕복 ---

    private static final ArticleId V2_ARTICLE = new ArticleId("volatility-is-not-loss");
    private static final TermId SWING = new TermId("price-swing");

    private static final List<ArticleBlock> V2_BLOCKS =
            List.of(
                    new KeySummaryBlock(List.of(RichText.text("첫 줄"), RichText.text("둘째 줄"))),
                    new HeadingBlock("소제목"),
                    new ParagraphBlock(
                            new RichText(
                                    List.of(
                                            new TextRun("글자 "),
                                            new TermMentionRun(MAX_DRAWDOWN, "가장 크게 줄었던 폭"),
                                            new SourceRun(
                                                    new SourceRef("출처", "https://example.com"))))),
                    new TermBlock(SWING),
                    new HypotheticalBlock(
                            new RichText(
                                    List.of(
                                            new TextRun("100만 원이 "),
                                            new TermMentionRun(SWING, "흔들림")))),
                    new MisconceptionBlock(RichText.text("틀린 생각"), RichText.text("바로잡기")),
                    new ListBlock(true, List.of(RichText.text("하나"), RichText.text("둘"))),
                    new TableBlock(
                            "",
                            List.of("가", "나"),
                            List.of(List.of(RichText.text("칸"), new RichText(List.of())))),
                    new DiagramBlock(
                            DiagramKind.LINE,
                            "제목",
                            "만 원",
                            List.of("1월", "2월"),
                            List.of(
                                    new Series(
                                            "가",
                                            List.of(
                                                    new BigDecimal("100"),
                                                    new BigDecimal("92.5")))),
                            "설명"),
                    new InstitutionNoteBlock(RichText.text("제도"), null, null),
                    new InstitutionNoteBlock(
                            RichText.text("제도"),
                            LocalDate.of(2026, 7, 1),
                            new SourceRef("제도 출처", "https://example.com/rule")));

    private static final List<LearningTerm> V2_TERMS =
            List.of(LearningTerm.v2(SWING, "흔들림", List.of("swing", "흔들림 폭"), "뜻", "읽는 법", "한계"));

    private static final List<QuizQuestion> V2_QUIZ =
            List.of(
                    new QuizQuestion(
                            new QuestionId("q1"),
                            "질문",
                            List.of(
                                    new QuizChoice(new ChoiceId("c2"), "가", false),
                                    new QuizChoice(new ChoiceId("c3"), "나", true)),
                            "풀이"));

    @Test
    @DisplayName("V2 초안의 블록 열 종류·글자 조각·용어 v2·확인 문제가 왕복하고 V2 모양으로 저장된다")
    void v2DraftRoundTrips() {
        LearningArticle draft = articleRepository.findByArticleKey(V2_ARTICLE).orElseThrow();
        LearningArticle saved =
                draft.saveDraft(
                        0,
                        new DraftContent(
                                "변동성은 손실일까?",
                                "질문",
                                204,
                                ArticleFormat.V2,
                                draft.placement(),
                                V2_BLOCKS,
                                null,
                                List.of(),
                                V2_TERMS,
                                List.of(
                                        new QuizQuestion(
                                                null,
                                                "질문",
                                                List.of(
                                                        new QuizChoice(null, "가", false),
                                                        new QuizChoice(null, "나", true)),
                                                "풀이"))),
                        false);
        articleRepository.save(saved);
        termRepository.replaceForArticle(V2_ARTICLE, V2_TERMS);
        flushAndClear();

        LearningArticle reloaded = articleRepository.findByArticleKey(V2_ARTICLE).orElseThrow();

        assertThat(reloaded.format()).isEqualTo(ArticleFormat.V2);
        assertThat(reloaded.blocks()).isEqualTo(V2_BLOCKS);
        assertThat(reloaded.terms()).isEqualTo(V2_TERMS);
        assertThat(reloaded.quiz()).isEqualTo(V2_QUIZ);
        assertThat(reloaded.quizSeq()).isEqualTo(3);
        assertThat(reloaded.placement())
                .isEqualTo(new Placement(new StageKey("returns-and-risk"), 4));
        Map<String, Object> stored =
                jdbcTemplate.queryForMap(
                        "SELECT jsonb_exists(body_blocks -> 2, 'content') AS paragraph_content,"
                                + " jsonb_exists(body_blocks -> 2, 'text') AS paragraph_text,"
                                + " quiz -> 0 -> 'choices' -> 1 ->> 'correct' AS correct"
                                + " FROM learning_article WHERE article_key = ?",
                        V2_ARTICLE.value());
        assertThat(stored.get("paragraph_content")).isEqualTo(true);
        assertThat(stored.get("paragraph_text")).isEqualTo(false);
        assertThat(stored.get("correct")).isEqualTo("true");
    }

    @Test
    @DisplayName("V2 판은 format·확인 문제(정답 식별자)·읽기 값과 함께 왕복하고 판 모양으로 저장된다")
    void v2SnapshotRoundTrips() {
        ArticleSnapshot snapshot =
                new ArticleSnapshot(
                        ArticleFormat.V2,
                        V2_ARTICLE,
                        "변동성은 손실일까?",
                        "질문",
                        204,
                        V2_BLOCKS,
                        null,
                        List.of(),
                        V2_TERMS,
                        7,
                        V2_QUIZ,
                        new ReadingEstimate(2480, 5, 100));

        ArticleRevision saved =
                revisionRepository.save(
                        new ArticleRevision(
                                null,
                                1,
                                Edition.CURRENT,
                                snapshot,
                                "learning-publish-checks-v2",
                                ArticleRevision.NOT_CHECKED,
                                7L,
                                Instant.parse("2026-11-19T01:00:00Z")));
        flushAndClear();

        assertThat(revisionRepository.findById(saved.id())).contains(saved);
        Map<String, Object> stored =
                jdbcTemplate.queryForMap(
                        "SELECT snapshot ->> 'format' AS format, edition,"
                                + " snapshot -> 'quiz' -> 0 ->> 'correctChoiceId' AS correct,"
                                + " jsonb_exists(snapshot -> 'quiz' -> 0 -> 'choices' -> 0, 'correct') AS has_flag,"
                                + " jsonb_exists(snapshot -> 'terms' -> 0, 'sentence') AS has_sentence"
                                + " FROM learning_article_revision WHERE id = ?",
                        saved.id());
        assertThat(stored.get("format")).isEqualTo("V2");
        assertThat(stored.get("edition")).isEqualTo("CURRENT");
        assertThat(stored.get("correct")).isEqualTo("c3");
        assertThat(stored.get("has_flag")).isEqualTo(false);
        assertThat(stored.get("has_sentence")).isEqualTo(false);
    }

    /** 온전한 V2 판 JSON. 테스트마다 필수 키 하나를 빼고 넣는다. */
    private static final String COMPLETE_V2_SNAPSHOT =
            """
            {"format":"V2","articleId":"returns-and-losses","title":"수익과 손실","question":"질문",
             "readingOrder":8,"bodyBlocks":[{"type":"paragraph","content":[{"text":"문단"}]}],
             "sources":[],"terms":[],"draftVersion":3,
             "quiz":[{"questionId":"q1","prompt":"p","choices":[{"choiceId":"c2","text":"t"}],"correctChoiceId":"c2","explanation":"e"}],
             "reading":{"bodyChars":2000,"readingMinutes":4,"minDwellSeconds":80}}
            """;

    private static final String V2_READING =
            ",\n \"reading\":{\"bodyChars\":2000,\"readingMinutes\":4,\"minDwellSeconds\":80}";

    private static final String V2_QUIZ_JSON =
            "\"quiz\":[{\"questionId\":\"q1\",\"prompt\":\"p\",\"choices\":[{\"choiceId\":\"c2\",\"text\":\"t\"}],"
                    + "\"correctChoiceId\":\"c2\",\"explanation\":\"e\"}]";

    @Test
    @DisplayName("온전한 V2 판 JSON은 읽힌다 (아래 실패 테스트의 대조군)")
    void completeV2SnapshotIsReadable() {
        long id = insertPublishedRevision(COMPLETE_V2_SNAPSHOT);

        ArticleSnapshot snapshot = revisionRepository.findById(id).orElseThrow().snapshot();

        assertThat(snapshot.format()).isEqualTo(ArticleFormat.V2);
        assertThat(snapshot.reading()).isEqualTo(new ReadingEstimate(2000, 4, 80));
        assertThat(snapshot.quiz()).hasSize(1);
    }

    @Test
    @DisplayName("CURRENT 판이 게시 중인 글도 공개 글 목록에 든다 (017 글 열거가 쓸 조회)")
    void publishedOrderedIncludesCurrentEdition() {
        long id = insertPublishedRevision(COMPLETE_V2_SNAPSHOT);

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT edition FROM learning_article_revision WHERE id = ?",
                                String.class,
                                id))
                .isEqualTo("CURRENT");
        assertThat(articleRepository.findPublishedOrdered())
                .containsExactly(
                        new LearningArticleSummaryResult("returns-and-losses", "수익과 손실", "질문", 8));
    }

    @Test
    @DisplayName("V2 판 JSON에 reading이 없으면 빈 값으로 채우지 않고 실패한다 (원칙 VI)")
    void missingV2ReadingFails() {
        assertThat(COMPLETE_V2_SNAPSHOT).contains(V2_READING);
        long id = insertPublishedRevision(COMPLETE_V2_SNAPSHOT.replace(V2_READING, ""));

        assertThatThrownBy(() -> revisionRepository.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reading");
    }

    @Test
    @DisplayName("V2 판 JSON에 확인 문제 목록이 없으면 빈 목록으로 채우지 않고 실패한다")
    void missingV2QuizFails() {
        assertThat(COMPLETE_V2_SNAPSHOT).contains(V2_QUIZ_JSON);
        long id = insertPublishedRevision(COMPLETE_V2_SNAPSHOT.replace(V2_QUIZ_JSON + ",", ""));

        assertThatThrownBy(() -> revisionRepository.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quiz");
    }

    @Test
    @DisplayName("V2 판의 문단이 9월 판 모양(text)이면 실패한다")
    void v2SnapshotWithV1ParagraphFails() {
        long id =
                insertPublishedRevision(
                        COMPLETE_V2_SNAPSHOT.replace(
                                "{\"type\":\"paragraph\",\"content\":[{\"text\":\"문단\"}]}",
                                "{\"type\":\"paragraph\",\"text\":\"문단\"}"));

        assertThatThrownBy(() -> revisionRepository.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content");
    }

    @Test
    @DisplayName("저장된 글자 조각에 모르는 키가 있으면 버리지 않고 실패한다 (원칙 VI)")
    void storedRunWithUnknownKeyFails() {
        long id =
                insertPublishedRevision(
                        COMPLETE_V2_SNAPSHOT.replace(
                                "{\"text\":\"문단\"}", "{\"text\":\"문단\",\"emphasis\":true}"));

        assertThatThrownBy(() -> revisionRepository.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown keys")
                .hasMessageContaining("emphasis");
    }

    @Test
    @DisplayName("저장된 글이 도메인 규칙을 어기면 요청 오류(400)가 아니라 저장 데이터 오류로 실패한다")
    void storedArticleViolationIsStateError() {
        flushAndClear();
        jdbcTemplate.update(
                "UPDATE learning_term SET meaning = '뜻' WHERE term_key = 'max-drawdown'");

        assertThatThrownBy(this::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("learning_article(returns-and-losses)")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("format이 없는(V1) 판에 V2 블록이 있으면 실패한다")
    void v1SnapshotWithV2BlockFails() {
        long id =
                insertPublishedRevision(
                        COMPLETE_SNAPSHOT.replace(
                                "{\"type\":\"paragraph\",\"text\":\"문단\"}",
                                "{\"type\":\"keySummary\",\"lines\":[]}"));

        assertThatThrownBy(() -> revisionRepository.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V2 block type in a V1 article: keySummary");
    }

    @Test
    @DisplayName("V2 판의 색인은 뜻 문장과 다른 이름을 쓰고, 여러 용어의 다른 이름을 한 번에 읽는다")
    void v2PublishedTermIndex() {
        long revisionId = insertPublishedRevision(COMPLETE_V2_SNAPSHOT);
        publishedTermRepository.replaceForArticle(ARTICLE, revisionId, V2_TERMS, ArticleFormat.V2);
        flushAndClear();

        assertThat(publishedTermRepository.findPublished(SWING))
                .hasValueSatisfying(term -> assertThat(term.sentence()).isEqualTo("뜻"));
        assertThat(publishedTermRepository.findPublishedAliases(List.of(SWING, MAX_DRAWDOWN)))
                .containsExactly(Map.entry(SWING, List.of("swing", "흔들림 폭")));
        assertThat(publishedTermRepository.findAllTermIds()).containsExactly(SWING);
        assertThat(termRepository.findAllTermIds()).hasSize(14);
    }

    @Test
    @DisplayName("CURRENT 판이 게시 중인 글만 공개 배치의 읽는 순서로 읽는다")
    void currentArticlesInReadingOrder() {
        insertPublishedRevision(COMPLETE_SNAPSHOT);
        assertThat(articleRepository.findCurrentInReadingOrder()).as("LEGACY 판은 세지 않는다").isEmpty();
        insertPublishedRevision(COMPLETE_V2_SNAPSHOT);

        assertThat(articleRepository.findCurrentInReadingOrder())
                .containsExactly(
                        new CurrentArticleEntry(
                                "returns-and-losses", "수익과 손실", "returns-and-risk", 2, 1));
    }
}
