package com.ssafy.thorp.learning.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.ssafy.thorp.support.PostgresTestSupport;
import com.ssafy.thorp.support.RepositoryTestSupport;
import com.ssafy.thorp.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * spec 013 마이그레이션을 빈 DB에 적용한 결과를 확인한다(quickstart 1 "마이그레이션"·"2판 마이그레이션").
 *
 * <p>매핑과 스키마가 어긋나면 {@link PostgresTestSupport}의 {@code ddl-auto=validate}로 컨텍스트 로딩부터 실패한다. 여기서는 초기
 * 자리와 CHECK·UNIQUE 제약(B08의 변경 기록과 용어 부분 유일 색인 포함)을 본다. <b>버전 번호를 적지 않는다</b>(backend/CLAUDE.md). 게시
 * 중이던 글의 공개 배치처럼 기존 데이터가 있어야 보이는 것은 {@code LearningStagesMigrationUpgradeIT}가 본다.
 *
 * <p><b>거절 테스트는 걸린 제약의 이름까지 단언한다</b>({@link #assertRejectedBy}). 예외 종류만 보면 다른 제약(NOT NULL·외래키)이 먼저
 * 걸려도 통과한다. 2판에서 {@code stage_id}·{@code stage_position}에 NOT NULL이 걸렸으므로 INSERT는 단계 배치를 채워 대상 제약만
 * 걸리게 한다.
 *
 * <p>제약 위반 단언은 테스트마다 하나다. PostgreSQL은 실패한 문장 뒤 같은 트랜잭션의 다음 문장을 모두 거절하고, {@code @DataJpaTest}는 테스트
 * 하나를 한 트랜잭션으로 묶어 되돌린다.
 */
class LearningMigrationTest extends RepositoryTestSupport {

    /** data-model §13-1의 열네 용어. */
    private static final List<String> SEED_TERM_KEYS =
            List.of(
                    "sector",
                    "representative-etf",
                    "weight",
                    "rebalancing",
                    "fixed-weight",
                    "equal-weight",
                    "volatility",
                    "inverse-volatility",
                    "sector-momentum",
                    "minimum-variance",
                    "backtest",
                    "transaction-cost",
                    "net-total-return",
                    "max-drawdown");

    /** data-model §13-2의 여섯 단계: stageKey, 번호, 이름, 수준, 한 줄 설명. */
    private static final List<List<Object>> STAGES =
            List.of(
                    List.of(
                            "etf-market-basics",
                            1,
                            "ETF와 국내 시장 기초",
                            "입문",
                            "ETF가 무엇을 담고 있고 가격과 비용이 어떻게 정해지는지 봅니다."),
                    List.of(
                            "returns-and-risk",
                            2,
                            "수익과 위험 읽기",
                            "입문",
                            "수익률, 하락 폭, 회복 기간, 변동성을 읽는 법을 익힙니다."),
                    List.of(
                            "allocation-rules",
                            3,
                            "배분 규칙과 설정",
                            "입문~초급",
                            "다섯 가지 배분 규칙과 설정 항목이 결과를 어떻게 바꾸는지 봅니다."),
                    List.of(
                            "backtest-and-costs",
                            4,
                            "백테스트와 비용",
                            "초급",
                            "같은 조건에서 비교하는 법과 거래비용, 회전율의 영향을 봅니다."),
                    List.of(
                            "pitfalls-and-validation",
                            5,
                            "함정과 검증",
                            "초급",
                            "좋아 보이는 과거 결과를 믿기 전에 확인할 점을 봅니다."),
                    List.of(
                            "economy-and-regimes",
                            6,
                            "경제환경과 국면",
                            "초급",
                            "시장 흐름의 분야와 비슷했던 때를 찾는 방법을 봅니다."));

    /** data-model §13-3의 27편: 번호, articleId, 제목, 형식. 기존 아홉 글은 V1, 새 18편은 V2다. */
    private static final List<List<String>> ARTICLES =
            List.of(
                    List.of("1-1", "sectors-and-etf", "섹터와 ETF", "V1"),
                    List.of("1-2", "etf-tracking-difference", "ETF 가격은 왜 지수와 똑같이 움직이지 않을까?", "V2"),
                    List.of("1-3", "etf-distributions", "분배금을 받으면 돈이 늘어난 걸까?", "V2"),
                    List.of("1-4", "etf-fees", "ETF 보수는 어디로 빠져나갈까?", "V2"),
                    List.of("2-1", "returns-and-losses", "수익과 손실", "V1"),
                    List.of("2-2", "same-return-different-feel", "같은 수익률인데 왜 느낌이 다를까?", "V2"),
                    List.of("2-3", "recovery-period", "떨어진 뒤 얼마나 걸려 돌아왔을까?", "V2"),
                    List.of("2-4", "volatility-is-not-loss", "변동성은 손실일까?", "V2"),
                    List.of("3-1", "weights-and-rebalancing", "비중과 리밸런싱", "V1"),
                    List.of("3-2", "diversification-and-correlation", "나누기만 하면 분산일까?", "V2"),
                    List.of("3-3", "inverse-volatility", "변동성에 따라 나누기", "V1"),
                    List.of("3-4", "sector-momentum", "섹터 모멘텀", "V1"),
                    List.of("3-5", "minimum-variance", "최소분산", "V1"),
                    List.of("3-6", "why-lookback-period", "관측 기간은 왜 고르는 항목일까?", "V2"),
                    List.of("3-7", "skip-recent-month", "왜 최근 한 달을 빼고 볼까?", "V2"),
                    List.of("3-8", "top-n-or-threshold", "상위 몇 개와 기준 수익률 이상은 어떻게 다를까?", "V2"),
                    List.of("3-9", "why-weight-cap", "비중 상한은 왜 둘까?", "V2"),
                    List.of("4-1", "backtest", "백테스트", "V1"),
                    List.of("4-2", "same-conditions", "같은 조건에서 비교하기란 무엇일까?", "V2"),
                    List.of("4-3", "transaction-cost", "거래비용", "V1"),
                    List.of("4-4", "turnover", "회전율이 높으면 왜 결과가 뒤집힐까?", "V2"),
                    List.of("4-5", "short-history", "자료 기간이 짧으면 무엇을 알 수 없을까?", "V2"),
                    List.of("5-1", "past-is-not-future", "과거가 미래를 보장하지 않는 이유", "V1"),
                    List.of("5-2", "best-of-many-settings", "50개 설정 중 1등은 왜 믿기 어려울까?", "V2"),
                    List.of("5-3", "neighboring-settings", "이웃 설정도 좋은가?", "V2"),
                    List.of("6-1", "market-flow-fields", "시장 흐름의 7개 분야는 무엇을 보여줄까?", "V2"),
                    List.of("6-2", "similar-periods", "'비슷했던 때'는 어떻게 찾을까?", "V2"));

    /** 9월 판 아홉 글의 읽는 순서. 첫 단계 공개 전 목록이 이 순서를 쓴다(R15). */
    private static final Map<String, Integer> LEGACY_READING_ORDER =
            Map.of(
                    "sectors-and-etf", 1,
                    "weights-and-rebalancing", 2,
                    "inverse-volatility", 3,
                    "sector-momentum", 4,
                    "minimum-variance", 5,
                    "backtest", 6,
                    "transaction-cost", 7,
                    "returns-and-losses", 8,
                    "past-is-not-future", 9);

    /** 나의 학습 행을 넣을 사용자. */
    private static final long LEARNER = 9101;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("여섯 단계가 번호·이름·수준·한 줄 설명대로 생기고 모두 공개 전이다")
    void seedsSixUnreleasedStages() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT stage_key, number, name, level_label, summary, released_at,"
                                + " released_by_user_id FROM learning_stage ORDER BY number");

        assertThat(rows)
                .extracting(
                        row ->
                                List.of(
                                        row.get("stage_key"),
                                        ((Number) row.get("number")).intValue(),
                                        row.get("name"),
                                        row.get("level_label"),
                                        row.get("summary")))
                .containsExactlyElementsOf(STAGES);
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("released_at")).isNull();
                            assertThat(row.get("released_by_user_id")).isNull();
                        });
    }

    @Test
    @DisplayName("27편이 단계와 단계 안 순서대로 놓이고 기존 아홉 글은 V1, 새 18편은 V2다")
    void placesTwentySevenArticles() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT s.number, a.stage_position, a.article_key, a.title, a.format"
                                + " FROM learning_article a JOIN learning_stage s ON s.id = a.stage_id"
                                + " ORDER BY s.number, a.stage_position");

        List<List<String>> actual = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            actual.add(
                    List.of(
                            row.get("number") + "-" + row.get("stage_position"),
                            (String) row.get("article_key"),
                            (String) row.get("title"),
                            (String) row.get("format")));
        }
        assertThat(actual).containsExactlyElementsOf(ARTICLES);
    }

    @Test
    @DisplayName("모든 글이 빈 초안이고 공개 배치·확인 문제가 없다. 기존 아홉 글의 읽는 순서는 그대로다")
    void allArticlesAreEmptyDrafts() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT article_key, format, status, question, body_blocks::text AS blocks,"
                                + " example IS NULL AS no_example, quiz::text AS quiz, quiz_seq,"
                                + " published_revision_id, published_stage_id, published_stage_position,"
                                + " draft_version, reading_order FROM learning_article");

        assertThat(rows).hasSize(27);
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("status")).isEqualTo("DRAFT");
                            assertThat(row.get("question")).isEqualTo("");
                            assertThat(row.get("blocks")).isEqualTo("[]");
                            assertThat(row.get("no_example")).isEqualTo(true);
                            assertThat(row.get("quiz")).isEqualTo("[]");
                            assertThat(row.get("quiz_seq")).isEqualTo(0);
                            assertThat(row.get("published_revision_id")).isNull();
                            assertThat(row.get("published_stage_id")).isNull();
                            assertThat(row.get("published_stage_position")).isNull();
                            assertThat(row.get("draft_version")).isEqualTo(0);
                        });
        assertThat(rows)
                .filteredOn(row -> "V1".equals(row.get("format")))
                .extracting(row -> row.get("article_key"), row -> row.get("reading_order"))
                .containsExactlyInAnyOrderElementsOf(
                        LEGACY_READING_ORDER.entrySet().stream()
                                .map(entry -> tuple(entry.getKey(), entry.getValue()))
                                .toList());
    }

    @Test
    @DisplayName("새 18편의 읽는 순서 값은 단계 번호 × 100 + 단계 안 순서다(999 상한)")
    void newArticlesReadingOrderFollowsPlacement() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT s.number, a.stage_position, a.reading_order FROM learning_article a"
                                + " JOIN learning_stage s ON s.id = a.stage_id WHERE a.format = 'V2'");

        assertThat(rows).hasSize(18);
        assertThat(rows)
                .allSatisfy(
                        row ->
                                assertThat(row.get("reading_order"))
                                        .isEqualTo(
                                                Math.min(
                                                        999,
                                                        ((Number) row.get("number")).intValue()
                                                                        * 100
                                                                + ((Number)
                                                                                row.get(
                                                                                        "stage_position"))
                                                                        .intValue())));
    }

    @Test
    @DisplayName("열네 용어가 모두 있고 문장·다른 이름·세 문장이 비어 있다")
    void seedsFourteenEmptyTerms() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT term_key, sentence, aliases::text AS aliases, meaning, reading, caveat"
                                + " FROM learning_term");

        assertThat(rows)
                .extracting(row -> row.get("term_key"))
                .containsExactlyInAnyOrderElementsOf(SEED_TERM_KEYS);
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("sentence")).isEqualTo("");
                            assertThat(row.get("aliases")).isEqualTo("[]");
                            assertThat(row.get("meaning")).isEqualTo("");
                            assertThat(row.get("reading")).isEqualTo("");
                            assertThat(row.get("caveat")).isEqualTo("");
                        });
    }

    @Test
    @DisplayName("공개 판·기록·색인은 비어 있다")
    void publicationTablesStartEmpty() {
        assertThat(count("learning_article_revision")).isZero();
        assertThat(count("learning_publication_event")).isZero();
        assertThat(count("learning_published_term")).isZero();
    }

    @Test
    @DisplayName("PUBLISHED인데 공개 판이 없는 행은 그 CHECK로 거절된다")
    void rejectsPublishedWithoutRevision() {
        assertRejectedBy(
                "ck_learning_article_published_revision",
                () -> insertArticle("no-revision", "PUBLISHED", "NULL", "NULL"));
    }

    @Test
    @DisplayName("형식이 틀린 article_key는 그 CHECK로 거절된다")
    void rejectsInvalidArticleKey() {
        assertRejectedBy(
                "ck_learning_article_key_format",
                () -> insertArticle("Bad_Key", "DRAFT", "NULL", "NULL"));
    }

    @Test
    @DisplayName("공개 판 없이 공개 배치 단계만 있는 행은 그 CHECK로 거절된다")
    void rejectsPublishedStageWithoutRevision() {
        assertRejectedBy(
                "ck_learning_article_published_stage",
                () -> insertArticle("stage-only", "DRAFT", stageIdOf("returns-and-risk"), "1"));
    }

    @Test
    @DisplayName("공개 배치 단계 없이 단계 안 순서만 있는 행은 그 CHECK로 거절된다")
    void rejectsPublishedPositionWithoutStage() {
        assertRejectedBy(
                "ck_learning_article_published_position",
                () -> insertArticle("position-only", "DRAFT", "NULL", "1"));
    }

    @Test
    @DisplayName("단계의 공개일과 공개한 계정 가운데 하나만 채우는 갱신은 그 CHECK로 거절된다")
    void rejectsHalfReleasedStage() {
        assertRejectedBy(
                "ck_learning_stage_released",
                () ->
                        jdbcTemplate.update(
                                "UPDATE learning_stage SET released_at = now()"
                                        + " WHERE stage_key = 'etf-market-basics'"));
    }

    @Test
    @DisplayName("V1·V2 밖의 형식은 그 CHECK로 거절된다")
    void rejectsUnknownFormat() {
        assertRejectedBy(
                "ck_learning_article_format",
                () ->
                        jdbcTemplate.update(
                                "UPDATE learning_article SET format = 'V3'"
                                        + " WHERE article_key = 'backtest'"));
    }

    @Test
    @DisplayName("같은 글의 판 번호가 겹치면 그 UNIQUE로 거절된다")
    void rejectsDuplicateRevisionNo() {
        long articleId = articleIdOf("backtest");
        insertRevision(articleId, 1);

        assertRejectedBy("uq_learning_article_revision_no", () -> insertRevision(articleId, 1));
    }

    @Test
    @DisplayName("edition을 적지 않은 판은 LEGACY다")
    void editionDefaultsToLegacy() {
        insertRevision(articleIdOf("backtest"), 1);

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT edition FROM learning_article_revision", String.class))
                .isEqualTo("LEGACY");
    }

    // --- B08: 변경 기록·첫 공개 시각·넘겨받기 (data-model §3-2·§3-3·§3-7) ---

    @Test
    @DisplayName("변경 기록은 비어 있고, 모든 글의 첫 공개 시각과 모든 용어의 넘겨받기가 비어 있다")
    void stageReleaseColumnsStartEmpty() {
        assertThat(count("learning_release_log")).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM learning_article WHERE first_released_at IS NOT NULL",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM learning_term"
                                        + " WHERE transfer_from_article_id IS NOT NULL",
                                Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("같은 term_key를 소유 행 둘로 넣으면 소유 부분 유일 색인으로 거절된다")
    void rejectsTwoOwnedRowsWithSameKey() {
        assertRejectedBy(
                "uq_learning_term_key_owned", () -> insertTerm("sector", "backtest", null));
    }

    @Test
    @DisplayName("같은 term_key라도 소유 행 하나와 넘겨받는 행 하나는 함께 있을 수 있다")
    void allowsOwnedAndIncomingRowWithSameKey() {
        insertTerm("volatility", "volatility-is-not-loss", "inverse-volatility");

        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT a.article_key FROM learning_term t"
                                        + " JOIN learning_article a ON a.id = t.article_id"
                                        + " WHERE t.term_key = 'volatility' ORDER BY t.id",
                                String.class))
                .containsExactly("inverse-volatility", "volatility-is-not-loss");
    }

    @Test
    @DisplayName("같은 term_key를 넘겨받는 행 둘로 넣으면 넘겨받기 부분 유일 색인으로 거절된다")
    void rejectsTwoIncomingRowsWithSameKey() {
        insertTerm("volatility", "volatility-is-not-loss", "inverse-volatility");

        assertRejectedBy(
                "uq_learning_term_key_incoming",
                () -> insertTerm("volatility", "recovery-period", "inverse-volatility"));
    }

    @Test
    @DisplayName("자기 글에서 넘겨받는 행은 그 CHECK로 거절된다")
    void rejectsSelfTransfer() {
        assertRejectedBy(
                "ck_learning_term_transfer_not_self",
                () -> insertTerm("new-term", "backtest", "backtest"));
    }

    @Test
    @DisplayName("글을 가리키지 않는 ARTICLE_ADDED 줄은 그 CHECK로 거절된다")
    void rejectsArticleEntryWithoutArticle() {
        assertRejectedBy(
                "ck_learning_release_log_article",
                () -> insertReleaseLog("article:etf-fees", "ARTICLE_ADDED", "NULL"));
    }

    @Test
    @DisplayName("글을 가리키는 STAGE_RELEASED 줄은 그 CHECK로 거절된다")
    void rejectsStageEntryWithArticle() {
        assertRejectedBy(
                "ck_learning_release_log_article",
                () ->
                        insertReleaseLog(
                                "stage:etf-market-basics",
                                "STAGE_RELEASED",
                                String.valueOf(articleIdOf("etf-fees"))));
    }

    @Test
    @DisplayName("두 종류 밖의 줄은 그 CHECK로 거절된다")
    void rejectsUnknownReleaseLogKind() {
        assertRejectedBy(
                "ck_learning_release_log_kind",
                () -> insertReleaseLog("stage:etf-market-basics", "STAGE_HIDDEN", "NULL"));
    }

    @Test
    @DisplayName("같은 entry_key의 줄은 그 UNIQUE로 거절된다")
    void rejectsDuplicateEntryKey() {
        insertReleaseLog("stage:etf-market-basics", "STAGE_RELEASED", "NULL");

        assertRejectedBy(
                "uq_learning_release_log_entry_key",
                () -> insertReleaseLog("stage:etf-market-basics", "STAGE_RELEASED", "NULL"));
    }

    // ── 나의 학습 네 테이블(B09, data-model §11) ─────────────────────────────
    // JPA 엔티티가 없어 ddl-auto=validate가 보지 않는다. 구조는 여기서 지킨다.

    @Test
    @DisplayName("나의 학습 네 테이블은 비어 있다")
    void learnerTablesStartEmpty() {
        assertThat(count("learning_progress")).isZero();
        assertThat(count("learning_answer")).isZero();
        assertThat(count("learning_exp_entry")).isZero();
        assertThat(count("learning_badge_award")).isZero();
    }

    @Test
    @DisplayName("읽은 비율이 0~100 밖이면 그 CHECK로 거절된다")
    void rejectsPercentOutOfRange() {
        long user = TestUsers.ensure(jdbcTemplate, LEARNER);

        assertRejectedBy(
                "ck_learning_progress_percent",
                () -> insertProgress(user, "backtest", 101, "NULL", "NULL"));
    }

    @Test
    @DisplayName("FIRST_ANSWER·RETRY 밖의 익힘 경로는 그 CHECK로 거절된다")
    void rejectsUnknownMasteredVia() {
        long user = TestUsers.ensure(jdbcTemplate, LEARNER);

        assertRejectedBy(
                "ck_learning_progress_mastered_via",
                () -> insertProgress(user, "backtest", 10, "now()", "'GUESS'"));
    }

    @Test
    @DisplayName("익힘 시각과 익힘 경로 가운데 하나만 있으면 그 CHECK로 거절된다")
    void rejectsHalfMastered() {
        long user = TestUsers.ensure(jdbcTemplate, LEARNER);

        assertRejectedBy(
                "ck_learning_progress_mastered",
                () -> insertProgress(user, "backtest", 10, "now()", "NULL"));
    }

    @Test
    @DisplayName("같은 (사용자, 글)의 두 번째 진행 행은 기본키로 거절된다")
    void rejectsSecondProgressRow() {
        long user = TestUsers.ensure(jdbcTemplate, LEARNER);
        insertProgress(user, "backtest", 10, "NULL", "NULL");

        assertRejectedBy(
                "pk_learning_progress", () -> insertProgress(user, "backtest", 20, "NULL", "NULL"));
    }

    @Test
    @DisplayName("단계 완주 줄이 글을 가리키면 그 CHECK로 거절된다(단계 완주는 단계만, 나머지는 글만)")
    void rejectsStageEntryPointingToArticle() {
        long user = TestUsers.ensure(jdbcTemplate, LEARNER);

        assertRejectedBy(
                "ck_learning_exp_entry_target",
                () -> insertExp(user, "STAGE_COMPLETED", "stage:returns-and-risk", 50));
    }

    @Test
    @DisplayName("네 종류 밖의 장부 줄은 그 CHECK로 거절된다")
    void rejectsUnknownExpKind() {
        long user = TestUsers.ensure(jdbcTemplate, LEARNER);

        assertRejectedBy(
                "ck_learning_exp_entry_kind", () -> insertExp(user, "STREAK", "streak:1", 20));
    }

    @Test
    @DisplayName("1~100 밖의 점수는 그 CHECK로 거절된다")
    void rejectsPointsOutOfRange() {
        long user = TestUsers.ensure(jdbcTemplate, LEARNER);

        assertRejectedBy(
                "ck_learning_exp_entry_points",
                () -> insertExp(user, "ARTICLE_COMPLETED", "article:backtest", 0));
    }

    @Test
    @DisplayName("같은 (사용자, 종류, 대상 키)의 장부 줄은 그 UNIQUE로 거절된다")
    void rejectsDuplicateExpEntry() {
        long user = TestUsers.ensure(jdbcTemplate, LEARNER);
        insertExp(user, "ARTICLE_COMPLETED", "article:backtest", 20);

        assertRejectedBy(
                "uq_learning_exp_entry_ref",
                () -> insertExp(user, "ARTICLE_COMPLETED", "article:backtest", 20));
    }

    @Test
    @DisplayName("같은 (사용자, 글, 문제)의 두 번째 첫 답은 기본키로 거절된다")
    void rejectsSecondFirstAnswer() {
        long user = TestUsers.ensure(jdbcTemplate, LEARNER);
        insertAnswer(user, "c1");

        assertRejectedBy("pk_learning_answer", () -> insertAnswer(user, "c2"));
    }

    @Test
    @DisplayName("같은 (사용자, 배지)의 두 번째 배지는 기본키로 거절된다")
    void rejectsSecondBadge() {
        long user = TestUsers.ensure(jdbcTemplate, LEARNER);
        insertBadge(user);

        assertRejectedBy("pk_learning_badge_award", () -> insertBadge(user));
    }

    /** 사용자 행이 지워지면 기록도 함께 지워진다(research R28, FR-025). */
    @Test
    @DisplayName("네 테이블의 user_id 외래키는 사용자가 지워질 때 CASCADE다")
    void learnerRowsFollowUserDeletion() {
        assertThat(deleteRule("learning_progress", "user_id")).isEqualTo("CASCADE");
        assertThat(deleteRule("learning_answer", "user_id")).isEqualTo("CASCADE");
        assertThat(deleteRule("learning_exp_entry", "user_id")).isEqualTo("CASCADE");
        assertThat(deleteRule("learning_badge_award", "user_id")).isEqualTo("CASCADE");
    }

    /**
     * 진행 행 하나. 익힘 두 열은 SQL 조각으로 받는다.
     *
     * @param masteredAt 예: {@code now()}, {@code NULL}
     * @param masteredVia 예: {@code 'RETRY'}, {@code NULL}
     */
    private void insertProgress(
            long userId, String articleKey, int percent, String masteredAt, String masteredVia) {
        jdbcTemplate.update(
                "INSERT INTO learning_progress (user_id, article_id, furthest_percent,"
                        + " last_opened_at, mastered_at, mastered_via, created_at, updated_at)"
                        + " VALUES (?, ?, ?, now(), "
                        + masteredAt
                        + ", "
                        + masteredVia
                        + ", now(), now())",
                userId,
                articleIdOf(articleKey),
                percent);
    }

    private void insertExp(long userId, String kind, String refKey, int points) {
        jdbcTemplate.update(
                "INSERT INTO learning_exp_entry (user_id, kind, ref_key, article_id, points,"
                        + " earned_at) VALUES (?, ?, ?, ?, ?, now())",
                userId,
                kind,
                refKey,
                articleIdOf("backtest"),
                points);
    }

    private void insertAnswer(long userId, String choiceKey) {
        jdbcTemplate.update(
                "INSERT INTO learning_answer (user_id, article_id, question_key, choice_key,"
                        + " correct, answered_at) VALUES (?, ?, 'q1', ?, true, now())",
                userId,
                articleIdOf("backtest"),
                choiceKey);
    }

    private void insertBadge(long userId) {
        jdbcTemplate.update(
                "INSERT INTO learning_badge_award (user_id, badge_key, earned_at)"
                        + " VALUES (?, 'read-1', now())",
                userId);
    }

    /** {@code RegimeLinkMigrationTest}와 같은 information_schema 조회. */
    private String deleteRule(String table, String column) {
        return jdbcTemplate.queryForObject(
                """
                SELECT rc.delete_rule
                  FROM information_schema.referential_constraints rc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = rc.constraint_name
                   AND kcu.constraint_schema = rc.constraint_schema
                 WHERE kcu.table_schema = 'public'
                   AND kcu.table_name = ?
                   AND kcu.column_name = ?
                """,
                String.class,
                table,
                column);
    }

    /**
     * 용어 행 하나. 문장은 비운다.
     *
     * @param transferFrom 넘겨받는 행이면 지금 소유 글의 {@code articleId}. 소유 행이면 null
     */
    private void insertTerm(String termKey, String articleKey, String transferFrom) {
        jdbcTemplate.update(
                "INSERT INTO learning_term (term_key, article_id, label, sentence,"
                        + " transfer_from_article_id, created_at, updated_at)"
                        + " VALUES (?, ?, 't', '', ?, now(), now())",
                termKey,
                articleIdOf(articleKey),
                transferFrom == null ? null : articleIdOf(transferFrom));
    }

    /** 1단계에 변경 기록 한 줄. 글 대리키는 SQL 조각으로 받는다. */
    private void insertReleaseLog(String entryKey, String kind, String articleId) {
        jdbcTemplate.update(
                "INSERT INTO learning_release_log (entry_key, kind, stage_id, article_id, new_count,"
                        + " rewritten_count, actor_user_id, occurred_at)"
                        + " VALUES (?, ?, (SELECT id FROM learning_stage"
                        + " WHERE stage_key = 'etf-market-basics'), "
                        + articleId
                        + ", 1, 0, 7, now())",
                entryKey,
                kind);
    }

    /**
     * 걸린 제약이 {@code constraint}인지까지 본다. PostgreSQL 오류 문장에 제약 이름이 따옴표로 실린다({@code
     * UniqueConstraintViolations}와 같은 판별).
     */
    private static void assertRejectedBy(String constraint, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        DataIntegrityViolationException.class,
                        violation ->
                                assertThat(violation.getMostSpecificCause().getMessage())
                                        .contains("\"" + constraint + "\""));
    }

    /** 초안 배치(1단계 1번)를 채워 넣는다. 공개 배치 두 열은 SQL 조각으로 받는다. */
    private void insertArticle(
            String articleKey, String status, String publishedStageId, String publishedPosition) {
        jdbcTemplate.update(
                "INSERT INTO learning_article (article_key, title, question, reading_order, status,"
                        + " stage_id, stage_position, published_stage_id, published_stage_position,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 't', '', 1, ?, (SELECT id FROM learning_stage"
                        + " WHERE stage_key = 'etf-market-basics'), 1, "
                        + publishedStageId
                        + ", "
                        + publishedPosition
                        + ", now(), now())",
                articleKey,
                status);
    }

    private String stageIdOf(String stageKey) {
        return String.valueOf(
                jdbcTemplate.queryForObject(
                        "SELECT id FROM learning_stage WHERE stage_key = ?", Long.class, stageKey));
    }

    private long articleIdOf(String articleKey) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM learning_article WHERE article_key = ?", Long.class, articleKey);
    }

    private void insertRevision(long articleId, int revisionNo) {
        jdbcTemplate.update(
                "INSERT INTO learning_article_revision (article_id, revision_no, snapshot,"
                        + " check_version, sector_map_version, published_by_user_id, published_at)"
                        + " VALUES (?, ?, '{}'::jsonb, 'learning-publish-checks-v1', 'SEM-1', 1, now())",
                articleId,
                revisionNo);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
