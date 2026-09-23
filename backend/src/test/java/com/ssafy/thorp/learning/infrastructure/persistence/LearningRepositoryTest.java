package com.ssafy.thorp.learning.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.ssafy.thorp.backtest.infrastructure.messaging.MessageJsonPersistenceConfig;
import com.ssafy.thorp.learning.application.result.LearningArticleSummaryResult;
import com.ssafy.thorp.learning.application.result.LearningTermResult;
import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.ArticleSnapshot;
import com.ssafy.thorp.learning.domain.model.ArticleSource;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.PublicationEvent;
import com.ssafy.thorp.learning.domain.model.PublicationEvent.PublicationAction;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation.SectorWeight;
import com.ssafy.thorp.learning.domain.vo.TermId;
import com.ssafy.thorp.support.RepositoryTestSupport;
import java.time.Instant;
import java.util.List;
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
                                "수익과 손실",
                                "질문",
                                8,
                                BLOCKS,
                                EXAMPLE,
                                SOURCES,
                                draft.terms()));
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
        assertThat(termRepository.findOwnerArticleKey(NET_TOTAL_RETURN)).isEmpty();
        assertThat(termRepository.findOwnerArticleKey(MAX_DRAWDOWN)).contains(ARTICLE);
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
                new ArticleSnapshot(
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
                                snapshot,
                                "learning-publish-checks-v1",
                                "SEM-1",
                                7L,
                                publishedAt));
        articleRepository.save(load().publish(draft.draftVersion(), saved.id()));
        publishedTermRepository.replaceForArticle(ARTICLE, saved.id(), terms);
        eventRepository.append(
                new PublicationEvent(
                        null, ARTICLE, PublicationAction.PUBLISH, saved.id(), 7L, publishedAt));
        flushAndClear();

        assertThat(revisionRepository.findById(saved.id())).contains(saved);
        assertThat(revisionRepository.findByArticleId(ARTICLE)).containsExactly(saved);
        assertThat(articleRepository.findPublishedRevision(ARTICLE)).contains(saved);
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

        // 같은 판으로 색인을 다시 써도 같은 term_key가 UNIQUE에 걸리지 않고, 지우면 공개에서 빠진다.
        publishedTermRepository.replaceForArticle(ARTICLE, saved.id(), terms);
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
                new ArticleSnapshot(
                        ARTICLE, "t", "q", 1, List.of(), EXAMPLE, List.of(), List.of(), 0);
        ArticleRevision existing =
                new ArticleRevision(99L, 1, snapshot, "v", "m", 1L, Instant.EPOCH);

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

    /** 판 JSON을 SQL로 직접 넣고 글을 그 판으로 게시한다. 저장 경로는 필수 키를 늘 쓰므로 빠진 판은 이렇게만 만들 수 있다. */
    private long insertPublishedRevision(String snapshotJson) {
        long articleId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM learning_article WHERE article_key = ?",
                        Long.class,
                        ARTICLE.value());
        long revisionId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO learning_article_revision (article_id, revision_no, snapshot,"
                                + " check_version, sector_map_version, published_by_user_id,"
                                + " published_at) VALUES (?, 1, CAST(? AS JSONB), 'v', 'm', 1, now())"
                                + " RETURNING id",
                        Long.class,
                        articleId,
                        snapshotJson);
        jdbcTemplate.update(
                "UPDATE learning_article SET status = 'PUBLISHED', published_revision_id = ?"
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
}
