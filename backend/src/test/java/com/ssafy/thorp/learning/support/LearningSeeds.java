package com.ssafy.thorp.learning.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 배우기 통합 테스트용 시드. 판·게시 용어 색인·글 상태·단계 공개 상태와 001 대응표를 JDBC로 직접 심는다.
 *
 * <p>공개 조회 테스트는 게시 유스케이스를 거치지 않고 판을 SQL로 심는다. 조회가 "판 → 응답" 변환만 확인하게 하려는 것이다. 대응표도 백엔드에 쓰기 경로가
 * 없으므로({@code SectorApiIT}와 같은 사정) SQL로 넣는다. 조회만 보는 테스트는 단계 공개와 변경 기록도 SQL로 심는다. 단계 공개 유스케이스는
 * {@code LearningStageReleaseIT}가 본다.
 *
 * <p><b>컨텍스트와 DB 컨테이너를 다른 테스트와 공유한다.</b> 이 클래스가 심은 것과 관리 API가 바꾼 초안은 {@link #reset()}이 마이그레이션 직후
 * 상태로 되돌린다. {@code LearningMigrationTest}는 27편이 모두 빈 초안이고 용어 문장이 비어 있으며 단계가 모두 공개 전이기를 기대한다.
 *
 * <p><b>대응표는 {@code MarketCatalogSeed}와 함께 쓴다.</b> 그 시드가 검사마다 자기 대응표를 열어 두고, 열린 대응표는 하나뿐이어야
 * 한다({@code uk_sector_etf_map_version_open}). 그래서 {@link #reset()}은 열린 대응표를 모두 닫아 "대응표 없음"에서 시작하고,
 * {@link #seedSectorMap}은 다른 열린 버전을 닫은 뒤 자기 버전을 연다. 닫힌 시드 대응표는 다음 검사 전에 {@code MarketCatalogSeed}가
 * 다시 연다.
 */
public final class LearningSeeds {

    public static final String MAP_VERSION = "LEARNING-IT-MAP";

    private static final String CHECK_VERSION = "learning-publish-checks-v1";
    private static final String CHECK_VERSION_V2 = "learning-publish-checks-v2";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LearningSeeds(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * data-model §13-3 27편. articleId, 제목, 읽는 순서, 형식, 단계, 단계 안 순서이고 마이그레이션과 같은 값이다. 기존 아홉 글의 읽는 순서는
     * 9월 판 값, 새 18편은 단계 번호 × 100 + 단계 안 순서다.
     */
    private static final List<List<Object>> INITIAL_ARTICLES =
            List.of(
                    article("sectors-and-etf", "섹터와 ETF", 1, "V1", "etf-market-basics", 1),
                    article(
                            "etf-tracking-difference",
                            "ETF 가격은 왜 지수와 똑같이 움직이지 않을까?",
                            102,
                            "V2",
                            "etf-market-basics",
                            2),
                    article(
                            "etf-distributions",
                            "분배금을 받으면 돈이 늘어난 걸까?",
                            103,
                            "V2",
                            "etf-market-basics",
                            3),
                    article("etf-fees", "ETF 보수는 어디로 빠져나갈까?", 104, "V2", "etf-market-basics", 4),
                    article("returns-and-losses", "수익과 손실", 8, "V1", "returns-and-risk", 1),
                    article(
                            "same-return-different-feel",
                            "같은 수익률인데 왜 느낌이 다를까?",
                            202,
                            "V2",
                            "returns-and-risk",
                            2),
                    article(
                            "recovery-period",
                            "떨어진 뒤 얼마나 걸려 돌아왔을까?",
                            203,
                            "V2",
                            "returns-and-risk",
                            3),
                    article(
                            "volatility-is-not-loss",
                            "변동성은 손실일까?",
                            204,
                            "V2",
                            "returns-and-risk",
                            4),
                    article("weights-and-rebalancing", "비중과 리밸런싱", 2, "V1", "allocation-rules", 1),
                    article(
                            "diversification-and-correlation",
                            "나누기만 하면 분산일까?",
                            302,
                            "V2",
                            "allocation-rules",
                            2),
                    article("inverse-volatility", "변동성에 따라 나누기", 3, "V1", "allocation-rules", 3),
                    article("sector-momentum", "섹터 모멘텀", 4, "V1", "allocation-rules", 4),
                    article("minimum-variance", "최소분산", 5, "V1", "allocation-rules", 5),
                    article(
                            "why-lookback-period",
                            "관측 기간은 왜 고르는 항목일까?",
                            306,
                            "V2",
                            "allocation-rules",
                            6),
                    article(
                            "skip-recent-month",
                            "왜 최근 한 달을 빼고 볼까?",
                            307,
                            "V2",
                            "allocation-rules",
                            7),
                    article(
                            "top-n-or-threshold",
                            "상위 몇 개와 기준 수익률 이상은 어떻게 다를까?",
                            308,
                            "V2",
                            "allocation-rules",
                            8),
                    article("why-weight-cap", "비중 상한은 왜 둘까?", 309, "V2", "allocation-rules", 9),
                    article("backtest", "백테스트", 6, "V1", "backtest-and-costs", 1),
                    article(
                            "same-conditions",
                            "같은 조건에서 비교하기란 무엇일까?",
                            402,
                            "V2",
                            "backtest-and-costs",
                            2),
                    article("transaction-cost", "거래비용", 7, "V1", "backtest-and-costs", 3),
                    article("turnover", "회전율이 높으면 왜 결과가 뒤집힐까?", 404, "V2", "backtest-and-costs", 4),
                    article(
                            "short-history",
                            "자료 기간이 짧으면 무엇을 알 수 없을까?",
                            405,
                            "V2",
                            "backtest-and-costs",
                            5),
                    article(
                            "past-is-not-future",
                            "과거가 미래를 보장하지 않는 이유",
                            9,
                            "V1",
                            "pitfalls-and-validation",
                            1),
                    article(
                            "best-of-many-settings",
                            "50개 설정 중 1등은 왜 믿기 어려울까?",
                            502,
                            "V2",
                            "pitfalls-and-validation",
                            2),
                    article(
                            "neighboring-settings",
                            "이웃 설정도 좋은가?",
                            503,
                            "V2",
                            "pitfalls-and-validation",
                            3),
                    article(
                            "market-flow-fields",
                            "시장 흐름의 7개 분야는 무엇을 보여줄까?",
                            601,
                            "V2",
                            "economy-and-regimes",
                            1),
                    article(
                            "similar-periods",
                            "'비슷했던 때'는 어떻게 찾을까?",
                            602,
                            "V2",
                            "economy-and-regimes",
                            2));

    /** data-model §13-1 초기 자리의 용어. termId, articleId, 이름 순이다. */
    private static final String[][] INITIAL_TERMS = {
        {"sector", "sectors-and-etf", "섹터"},
        {"representative-etf", "sectors-and-etf", "대표 ETF"},
        {"weight", "weights-and-rebalancing", "비중"},
        {"rebalancing", "weights-and-rebalancing", "리밸런싱"},
        {"fixed-weight", "weights-and-rebalancing", "내가 정한 비중"},
        {"equal-weight", "weights-and-rebalancing", "똑같이 나누기"},
        {"volatility", "inverse-volatility", "변동성"},
        {"inverse-volatility", "inverse-volatility", "변동성에 따라 나누기"},
        {"sector-momentum", "sector-momentum", "섹터 모멘텀"},
        {"minimum-variance", "minimum-variance", "최소분산"},
        {"backtest", "backtest", "백테스트"},
        {"transaction-cost", "transaction-cost", "거래비용"},
        {"net-total-return", "returns-and-losses", "비용 차감 후 전체 수익률"},
        {"max-drawdown", "returns-and-losses", "가장 크게 줄었던 폭"}
    };

    private static List<Object> article(
            String articleId,
            String title,
            int readingOrder,
            String format,
            String stageKey,
            int stagePosition) {
        return List.of(articleId, title, readingOrder, format, stageKey, stagePosition);
    }

    /**
     * 마이그레이션 직후 상태로 되돌린다. 공개 판·기록·색인·변경 기록과 공개 배치·첫 공개 시각을 지우고, 관리 API가 만든 글을 지우고, 27편의 초안 컬럼(제목·읽는
     * 순서·형식·배치·확인 문제 포함)과 열네 용어(넘겨받기 없음)를 초기 자리로 되돌리고, 단계를 모두 공개 전으로 되돌린다. 이 클래스가 넣은 대응표를 지우고 남은 열린
     * 대응표도 닫는다.
     */
    public void reset() {
        // 나의 학습 기록을 맨 먼저 비운다. 남으면 다음 테스트의 경험치·배지 계산에 섞이고, learning_article을 가리키는
        // 외래키 때문에 관리 API가 만든 글을 지우는 아래 문장이 막힌다.
        jdbc.update("DELETE FROM learning_badge_award");
        jdbc.update("DELETE FROM learning_exp_entry");
        jdbc.update("DELETE FROM learning_answer");
        jdbc.update("DELETE FROM learning_progress");
        // 변경 기록은 단계·글을 가리키므로 글을 지우기 전에 비운다.
        jdbc.update("DELETE FROM learning_release_log");
        jdbc.update("DELETE FROM learning_published_term");
        jdbc.update("DELETE FROM learning_publication_event");
        // 공개 판 포인터와 공개 배치는 함께 있거나 함께 없다(테이블 CHECK). 한 문장에서 함께 비운다.
        jdbc.update(
                "UPDATE learning_article SET status = 'DRAFT', published_revision_id = NULL,"
                        + " published_stage_id = NULL, published_stage_position = NULL,"
                        + " first_released_at = NULL");
        jdbc.update("DELETE FROM learning_article_revision");
        jdbc.update("DELETE FROM learning_term");
        String[] initialKeys = new String[INITIAL_ARTICLES.size()];
        for (int i = 0; i < INITIAL_ARTICLES.size(); i++) {
            initialKeys[i] = (String) INITIAL_ARTICLES.get(i).get(0);
        }
        jdbc.update(
                "DELETE FROM learning_article WHERE NOT (article_key = ANY (?))",
                (Object) initialKeys);
        for (List<Object> article : INITIAL_ARTICLES) {
            jdbc.update(
                    """
                    UPDATE learning_article
                       SET title = ?, question = '', reading_order = ?, body_blocks = '[]'::jsonb,
                           example = NULL, sources = '[]'::jsonb, draft_version = 0, format = ?,
                           stage_id = (SELECT id FROM learning_stage WHERE stage_key = ?),
                           stage_position = ?, quiz = '[]'::jsonb, quiz_seq = 0
                     WHERE article_key = ?
                    """,
                    article.get(1),
                    article.get(2),
                    article.get(3),
                    article.get(4),
                    article.get(5),
                    article.get(0));
        }
        for (String[] term : INITIAL_TERMS) {
            jdbc.update(
                    """
                    INSERT INTO learning_term (term_key, article_id, label, sentence, created_at, updated_at)
                    SELECT ?, id, ?, '', now(), now() FROM learning_article WHERE article_key = ?
                    """,
                    term[0],
                    term[2],
                    term[1]);
        }
        jdbc.update("UPDATE learning_stage SET released_at = NULL, released_by_user_id = NULL");
        clearSectorMap();
        jdbc.update(
                "UPDATE sector_etf_map_version SET effective_to = effective_from + INTERVAL '1 second'"
                        + " WHERE effective_to IS NULL");
    }

    /** 단계를 공개된 것으로 표시한다. 단계 공개 유스케이스를 거치지 않으므로 판·변경 기록은 생기지 않는다. 공개한 계정은 관리 계정 7이다. */
    public void markStageReleased(String stageKey) {
        markStageReleased(stageKey, Instant.parse("2026-11-12T01:00:00Z"));
    }

    /** 단계를 그 시각에 공개된 것으로 표시한다. */
    public void markStageReleased(String stageKey, Instant releasedAt) {
        int updated =
                jdbc.update(
                        "UPDATE learning_stage SET released_at = CAST(? AS TIMESTAMPTZ),"
                                + " released_by_user_id = 7 WHERE stage_key = ?",
                        releasedAt.toString(),
                        stageKey);
        if (updated != 1) {
            throw new IllegalArgumentException("no learning stage: " + stageKey);
        }
    }

    /** 단계 공개 변경 기록 한 줄을 심는다. 다시 쓴 글 수는 제목 수다. */
    public void logStageReleased(
            String stageKey, int newCount, List<String> rewrittenTitles, Instant occurredAt) {
        jdbc.update(
                """
                INSERT INTO learning_release_log
                    (entry_key, kind, stage_id, new_count, rewritten_count, rewritten_titles,
                     actor_user_id, occurred_at)
                SELECT ?, 'STAGE_RELEASED', id, ?, ?, CAST(? AS JSONB), 7, CAST(? AS TIMESTAMPTZ)
                FROM learning_stage WHERE stage_key = ?
                """,
                "stage:" + stageKey,
                newCount,
                rewrittenTitles.size(),
                toJson(rewrittenTitles),
                occurredAt.toString(),
                stageKey);
    }

    /** 공개된 단계에 더한 글의 변경 기록 한 줄을 심는다. */
    public void logArticleAdded(
            String stageKey, String articleId, String title, Instant occurredAt) {
        jdbc.update(
                """
                INSERT INTO learning_release_log
                    (entry_key, kind, stage_id, article_id, new_count, rewritten_count,
                     article_title, actor_user_id, occurred_at)
                SELECT ?, 'ARTICLE_ADDED', s.id, a.id, 1, 0, ?, 7, CAST(? AS TIMESTAMPTZ)
                FROM learning_stage s, learning_article a
                WHERE s.stage_key = ? AND a.article_key = ?
                """,
                "article:" + articleId,
                title,
                occurredAt.toString(),
                stageKey,
                articleId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 공개 계약 응답({@code article.json} 모양의 {@code data})을 9월 판(LEGACY) 판으로 되돌려 심는다. 판의 {@code term}
     * 블록은 {@code termId}만 담고 이름·문장은 {@code terms}로 옮긴다. 응답 전용 필드({@code available}·{@code name} 등)는
     * 버린다.
     *
     * @param readingOrder 판의 읽는 순서. 초안 컬럼과 다르게 줄 수 있다
     * @return 심은 판의 대리키
     */
    public long publishFromResponse(JsonNode article, int readingOrder) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("articleId", article.get("articleId").asText());
        snapshot.put("title", article.get("title").asText());
        snapshot.put("question", article.get("question").asText());
        snapshot.put("readingOrder", readingOrder);
        ArrayNode blocks = snapshot.putArray("bodyBlocks");
        ArrayNode terms = objectMapper.createArrayNode();
        for (JsonNode block : article.path("blocks")) {
            ObjectNode stored = blocks.addObject().put("type", block.get("type").asText());
            if ("term".equals(block.get("type").asText())) {
                stored.put("termId", block.get("termId").asText());
                terms.addObject()
                        .put("termId", block.get("termId").asText())
                        .put("label", block.get("label").asText())
                        .put("sentence", block.get("sentence").asText());
            } else {
                stored.put("text", block.get("text").asText());
            }
        }
        ArrayNode sectors = snapshot.putObject("example").putArray("sectors");
        for (JsonNode sector : article.path("example").path("sectors")) {
            sectors.addObject()
                    .put("code", sector.get("code").asText())
                    .put("weightTenths", sector.get("weightTenths").asInt());
        }
        ArrayNode sources = snapshot.putArray("sources");
        for (JsonNode source : article.path("sources")) {
            ObjectNode stored = sources.addObject().put("label", source.get("label").asText());
            if (source.hasNonNull("url")) {
                stored.put("url", source.get("url").asText());
            }
        }
        snapshot.set("terms", terms);
        snapshot.put("draftVersion", 0);
        Instant publishedAt =
                article.hasNonNull("publishedAt")
                        ? Instant.parse(article.get("publishedAt").asText())
                        : Instant.parse("2026-09-20T01:00:00Z");
        return publish(snapshot, publishedAt, "LEGACY", CHECK_VERSION, MAP_VERSION);
    }

    /**
     * 목록 항목({@code articles-list.json}의 한 줄)만으로 최소 LEGACY 판을 심는다. 본문은 문단 하나, 예시는 두 섹터다.
     *
     * @return 심은 판의 대리키
     */
    public long publishMinimal(String articleId, String title, String question, int readingOrder) {
        return publishLegacy(articleId, title, question, readingOrder, "합성 문단");
    }

    /**
     * 본문이 {@code bodyChars}자(공백 없음) 문단 하나인 LEGACY 판을 심는다. 읽기 분은 조회 때 이 글자 수로 계산된다(500자에 1분, 반올림).
     *
     * @return 심은 판의 대리키
     */
    public long publishLegacyWithBody(
            String articleId, String title, String question, int readingOrder, int bodyChars) {
        return publishLegacy(articleId, title, question, readingOrder, "가".repeat(bodyChars));
    }

    /**
     * 용어 하나를 소유한 최소 LEGACY 판을 심는다. 본문은 문단과 그 용어 블록이고 게시 용어 색인에 그 용어의 설명 문장이 오른다.
     *
     * @return 심은 판의 대리키
     */
    public long publishLegacyWithTerm(
            String articleId, String title, String termId, String label, String sentence) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("articleId", articleId);
        snapshot.put("title", title);
        snapshot.put("question", "합성 질문");
        snapshot.put("readingOrder", 3);
        ArrayNode blocks = snapshot.putArray("bodyBlocks");
        blocks.addObject().put("type", "paragraph").put("text", "합성 문단");
        blocks.addObject().put("type", "term").put("termId", termId);
        ArrayNode sectors = snapshot.putObject("example").putArray("sectors");
        sectors.addObject().put("code", "SEMICONDUCTOR").put("weightTenths", 500);
        sectors.addObject().put("code", "BATTERY").put("weightTenths", 500);
        snapshot.putArray("sources");
        snapshot.putArray("terms")
                .addObject()
                .put("termId", termId)
                .put("label", label)
                .put("sentence", sentence);
        snapshot.put("draftVersion", 0);
        return publish(
                snapshot,
                Instant.parse("2026-09-20T01:00:00Z"),
                "LEGACY",
                CHECK_VERSION,
                MAP_VERSION);
    }

    private long publishLegacy(
            String articleId, String title, String question, int readingOrder, String body) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("articleId", articleId);
        snapshot.put("title", title);
        snapshot.put("question", question);
        snapshot.put("readingOrder", readingOrder);
        snapshot.putArray("bodyBlocks").addObject().put("type", "paragraph").put("text", body);
        ArrayNode sectors = snapshot.putObject("example").putArray("sectors");
        sectors.addObject().put("code", "SEMICONDUCTOR").put("weightTenths", 500);
        sectors.addObject().put("code", "BATTERY").put("weightTenths", 500);
        snapshot.putArray("sources");
        snapshot.putArray("terms");
        snapshot.put("draftVersion", 0);
        return publish(
                snapshot,
                Instant.parse("2026-09-20T01:00:00Z"),
                "LEGACY",
                CHECK_VERSION,
                MAP_VERSION);
    }

    /**
     * 공개 계약 응답({@code article-current.json} 모양의 {@code data})을 새 기준(CURRENT) 판으로 되돌려 심는다. 언급 조각의
     * {@code owned}·{@code aliases}, 용어 블록의 문장, 확인 문제의 {@code correctChoiceId}처럼 응답에서 펼친 값을 판
     * 모양(data-model §4-2)으로 되돌린다. 공개 배치는 그 글의 초안 배치다. 그 단계를 먼저 {@link #markStageReleased}로 공개해 둔다.
     *
     * @return 심은 판의 대리키
     */
    public long publishCurrentFromResponse(JsonNode article) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("format", "V2");
        snapshot.put("articleId", article.get("articleId").asText());
        snapshot.put("title", article.get("title").asText());
        snapshot.put("question", article.get("question").asText());
        snapshot.put("readingOrder", article.get("readingOrder").asInt());
        ArrayNode blocks = snapshot.putArray("bodyBlocks");
        Map<String, ObjectNode> terms = new LinkedHashMap<>();
        for (JsonNode block : article.path("blocks")) {
            ObjectNode stored = block.deepCopy();
            if ("term".equals(block.get("type").asText())) {
                ObjectNode term = objectMapper.createObjectNode();
                for (String key : List.of("termId", "label")) {
                    term.set(key, block.get(key));
                }
                term.set("aliases", block.get("aliases"));
                for (String key : List.of("meaning", "reading", "caveat")) {
                    term.set(key, block.get(key));
                }
                terms.put(block.get("termId").asText(), term);
                stored = objectMapper.createObjectNode();
                stored.put("type", "term").put("termId", block.get("termId").asText());
            } else {
                stripMentionViews(stored);
            }
            blocks.add(stored);
        }
        snapshot.putNull("example");
        snapshot.putArray("sources");
        snapshot.putArray("terms").addAll(terms.values());
        snapshot.put("draftVersion", 7);
        ArrayNode quiz = snapshot.putArray("quiz");
        for (JsonNode question : article.path("quiz")) {
            quiz.add(question.deepCopy());
        }
        snapshot.putObject("reading")
                .put("bodyChars", article.get("readingMinutes").asInt() * 500)
                .put("readingMinutes", article.get("readingMinutes").asInt())
                .put("minDwellSeconds", article.get("minDwellSeconds").asInt());
        return publish(
                snapshot,
                Instant.parse(article.get("publishedAt").asText()),
                "CURRENT",
                CHECK_VERSION_V2,
                "NOT_CHECKED");
    }

    /**
     * 본문이 문단 하나뿐인 최소 CURRENT 판을 심는다. 다른 글의 번호·다음 글·단계 글 수, 다른 글이 소유한 용어의 게시 색인을 만들 때 쓴다.
     *
     * @param terms 이 판이 소유하는 용어. termId → 다른 이름
     * @return 심은 판의 대리키
     */
    public long publishCurrentMinimal(
            String articleId, String title, Map<String, List<String>> terms) {
        return publishCurrent(articleId, title, "합성 질문", 4, terms, false);
    }

    /**
     * 본문이 문단 하나인 CURRENT 판을 심는다. 판의 읽기 값은 주어진 분이다(조회는 판 값을 그대로 쓴다). 커리큘럼처럼 판 값을 목록으로 보이는 조회에 쓴다.
     *
     * @param terms 이 판이 소유하는 용어. termId → 다른 이름
     * @param withExample 두 섹터 예시를 넣는다
     * @return 심은 판의 대리키
     */
    public long publishCurrent(
            String articleId,
            String title,
            String question,
            int readingMinutes,
            Map<String, List<String>> terms,
            boolean withExample) {
        return publishCurrent(
                articleId, title, question, readingMinutes, terms, withExample, Map.of());
    }

    /**
     * 확인 문제가 있는 CURRENT 판을 심는다. 나의 학습 테스트가 쓴다. 같은 글에 다시 부르면 다음 판 번호로 다시 게시한다(운영자가 문제를 바꿔 게시한 경우).
     *
     * @param termIds 이 판이 소유하는 용어. 다른 이름은 없다
     * @param quiz 문제 식별자 → 보기 식별자 목록. 첫 보기가 정답이다. 순서대로 판에 넣는다
     * @return 심은 판의 대리키
     */
    public long publishCurrentWithQuiz(
            String articleId, String title, List<String> termIds, Map<String, List<String>> quiz) {
        Map<String, List<String>> terms = new LinkedHashMap<>();
        termIds.forEach(termId -> terms.put(termId, List.of()));
        return publishCurrent(articleId, title, "합성 질문", 4, terms, false, quiz);
    }

    /** 게시 취소. 판·기록은 남기고 글을 초안으로 돌리며 그 글의 게시 용어 색인을 지운다(data-model §10-1). */
    public void unpublish(String articleId) {
        jdbc.update(
                "DELETE FROM learning_published_term WHERE article_id ="
                        + " (SELECT id FROM learning_article WHERE article_key = ?)",
                articleId);
        jdbc.update(
                "UPDATE learning_article SET status = 'DRAFT', published_revision_id = NULL,"
                        + " published_stage_id = NULL, published_stage_position = NULL"
                        + " WHERE article_key = ?",
                articleId);
    }

    private long publishCurrent(
            String articleId,
            String title,
            String question,
            int readingMinutes,
            Map<String, List<String>> terms,
            boolean withExample,
            Map<String, List<String>> quizSpec) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("format", "V2");
        snapshot.put("articleId", articleId);
        snapshot.put("title", title);
        snapshot.put("question", question);
        snapshot.put("readingOrder", 100);
        ArrayNode blocks = snapshot.putArray("bodyBlocks");
        blocks.addObject()
                .put("type", "paragraph")
                .putArray("content")
                .addObject()
                .put("text", "합성 문단");
        ArrayNode termNodes = snapshot.putArray("terms");
        terms.forEach(
                (termId, aliases) -> {
                    blocks.addObject().put("type", "term").put("termId", termId);
                    ObjectNode term =
                            termNodes
                                    .addObject()
                                    .put("termId", termId)
                                    .put("label", "합성 용어 " + termId);
                    ArrayNode aliasNodes = term.putArray("aliases");
                    aliases.forEach(aliasNodes::add);
                    term.put("meaning", "합성 뜻 " + termId)
                            .put("reading", "합성 읽는 법")
                            .put("caveat", "합성 한계");
                });
        if (withExample) {
            ArrayNode sectors = snapshot.putObject("example").putArray("sectors");
            sectors.addObject().put("code", "SEMICONDUCTOR").put("weightTenths", 500);
            sectors.addObject().put("code", "BATTERY").put("weightTenths", 500);
        } else {
            snapshot.putNull("example");
        }
        snapshot.putArray("sources");
        snapshot.put("draftVersion", 0);
        ArrayNode quiz = snapshot.putArray("quiz");
        quizSpec.forEach(
                (questionId, choiceIds) -> {
                    ObjectNode node =
                            quiz.addObject()
                                    .put("questionId", questionId)
                                    .put("prompt", "합성 문제 " + questionId);
                    ArrayNode choices = node.putArray("choices");
                    choiceIds.forEach(
                            choiceId ->
                                    choices.addObject()
                                            .put("choiceId", choiceId)
                                            .put("text", "합성 보기 " + choiceId));
                    node.put("correctChoiceId", choiceIds.get(0))
                            .put("explanation", "합성 풀이 " + questionId);
                });
        snapshot.putObject("reading")
                .put("bodyChars", readingMinutes * 500)
                .put("readingMinutes", readingMinutes)
                .put("minDwellSeconds", readingMinutes * 20);
        return publish(
                snapshot,
                Instant.parse("2026-11-12T01:00:00Z"),
                "CURRENT",
                CHECK_VERSION_V2,
                "NOT_CHECKED");
    }

    /** 응답의 언급 조각에서 서버가 채운 {@code owned}·{@code aliases}를 지운다. 판에는 없는 값이다. */
    private static void stripMentionViews(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (object.has("term") && object.has("owned")) {
                object.remove("owned");
                object.remove("aliases");
            }
            object.elements().forEachRemaining(LearningSeeds::stripMentionViews);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(LearningSeeds::stripMentionViews);
        }
    }

    /**
     * 판 하나를 넣고 글을 그 판으로 게시하며 판의 용어로 색인을 채운다. 공개 배치는 그 글의 초안 배치로 함께 채운다(테이블 CHECK). 게시 유스케이스가 한
     * 트랜잭션에서 할 일과 같은 결과다.
     */
    private long publish(
            ObjectNode snapshot,
            Instant publishedAt,
            String edition,
            String checkVersion,
            String mapVersion) {
        String articleKey = snapshot.get("articleId").asText();
        long articleId =
                jdbc.queryForObject(
                        "SELECT id FROM learning_article WHERE article_key = ?",
                        Long.class,
                        articleKey);
        Integer lastRevisionNo =
                jdbc.queryForObject(
                        "SELECT max(revision_no) FROM learning_article_revision WHERE article_id = ?",
                        Integer.class,
                        articleId);
        long revisionId =
                jdbc.queryForObject(
                        """
                        INSERT INTO learning_article_revision
                            (article_id, revision_no, snapshot, check_version, sector_map_version,
                             published_by_user_id, published_at, edition)
                        VALUES (?, ?, CAST(? AS JSONB), ?, ?, 7, CAST(? AS TIMESTAMPTZ), ?)
                        RETURNING id
                        """,
                        Long.class,
                        articleId,
                        lastRevisionNo == null ? 1 : lastRevisionNo + 1,
                        snapshot.toString(),
                        checkVersion,
                        mapVersion,
                        publishedAt.toString(),
                        edition);
        jdbc.update(
                "UPDATE learning_article SET status = 'PUBLISHED', published_revision_id = ?,"
                        + " published_stage_id = stage_id, published_stage_position = stage_position"
                        + " WHERE id = ?",
                revisionId,
                articleId);
        jdbc.update("DELETE FROM learning_published_term WHERE article_id = ?", articleId);
        boolean current = "CURRENT".equals(edition);
        for (JsonNode term : snapshot.path("terms")) {
            jdbc.update(
                    "INSERT INTO learning_published_term"
                            + " (term_key, article_id, revision_id, label, sentence, aliases)"
                            + " VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))",
                    term.get("termId").asText(),
                    articleId,
                    revisionId,
                    term.get("label").asText(),
                    term.get(current ? "meaning" : "sentence").asText(),
                    current ? term.get("aliases").toString() : "[]");
        }
        return revisionId;
    }

    /**
     * 열린 대응표 하나를 심는다. 섹터마다 대표 ETF를 하나씩 만든다.
     *
     * <p>다른 열린 버전을 먼저 닫는다. 이미 있는 섹터 행은 새로 넣지 않고 그 행을 쓴다. {@code MarketCatalogSeed}가 같은 코드를 먼저 넣어 두기
     * 때문이다. 이름은 그 행의 값이 쓰인다.
     */
    public void seedSectorMap(Map<String, String> namesByCode) {
        jdbc.update(
                "UPDATE sector_etf_map_version SET effective_to = effective_from + INTERVAL '1 second'"
                        + " WHERE effective_to IS NULL");
        Long mapVersionId =
                jdbc.queryForObject(
                        """
                        INSERT INTO sector_etf_map_version
                            (version, effective_from, effective_to, is_sample, published_at)
                        VALUES (?, TIMESTAMPTZ '2026-09-10 00:00:00+09', NULL, TRUE,
                                TIMESTAMPTZ '2026-09-10 00:00:00+09')
                        RETURNING id
                        """,
                        Long.class,
                        MAP_VERSION);
        int serial = 0;
        for (Map.Entry<String, String> sector : new LinkedHashMap<>(namesByCode).entrySet()) {
            serial++;
            jdbc.update(
                    "INSERT INTO sector (code, name, classification) VALUES (?, ?, 'INDUSTRY')"
                            + " ON CONFLICT (code) DO NOTHING",
                    sector.getKey(),
                    sector.getValue());
            long sectorId =
                    jdbc.queryForObject(
                            "SELECT id FROM sector WHERE code = ?", Long.class, sector.getKey());
            long etfId =
                    jdbc.queryForObject(
                            """
                            INSERT INTO representative_etf
                                (instrument_id, name, listed_on, benchmark_index, manager)
                            VALUES (?, ?, DATE '2010-01-04', '합성 지수', '합성 운용사')
                            RETURNING id
                            """,
                            Long.class,
                            etfInstrumentId(serial),
                            "합성 ETF " + serial);
            jdbc.update(
                    "INSERT INTO sector_etf_map_entry (map_version_id, sector_id, etf_id)"
                            + " VALUES (?, ?, ?)",
                    mapVersionId,
                    sectorId,
                    etfId);
        }
    }

    /** {@link #seedSectorMap}이 넣은 것을 FK 역순으로 지운다. 열린 버전이 남으면 다른 테스트의 대응표 INSERT가 막힌다. */
    public void clearSectorMap() {
        jdbc.update(
                "DELETE FROM sector_etf_map_entry WHERE map_version_id IN"
                        + " (SELECT id FROM sector_etf_map_version WHERE version = ?)",
                MAP_VERSION);
        jdbc.update("DELETE FROM sector_etf_map_version WHERE version = ?", MAP_VERSION);
        jdbc.update("DELETE FROM representative_etf WHERE instrument_id LIKE '9900__.XKRX'");
        // 이 클래스가 쓰는 코드라도 다른 대응표 항목이 가리키는 섹터 행은 남긴다. 다른 IT가 정리에 실패해
        // 남긴 항목 때문에 여기서 FK 위반이 나면 원인과 무관한 배우기 테스트 전부가 깨진다.
        jdbc.update(
                "DELETE FROM sector WHERE code IN ('SEMICONDUCTOR', 'BATTERY')"
                        + " AND NOT EXISTS (SELECT 1 FROM sector_etf_map_entry e WHERE e.sector_id = sector.id)");
    }

    private static String etfInstrumentId(int serial) {
        return String.format("9900%02d.XKRX", serial);
    }
}
