package com.ssafy.thorp.learning.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 배우기 통합 테스트용 시드. 판·게시 용어 색인·글 상태와 001 대응표를 JDBC로 직접 심는다.
 *
 * <p>게시 유스케이스(B02)가 아직 없어서 저장소 대신 SQL을 쓴다. 대응표도 백엔드에 쓰기 경로가 없으므로({@code SectorApiIT}와 같은 사정) SQL로
 * 넣는다.
 *
 * <p><b>컨텍스트와 DB 컨테이너를 다른 테스트와 공유한다.</b> 이 클래스가 심은 것은 {@link #reset()}이 마이그레이션 직후 상태로 되돌린다. {@code
 * LearningMigrationTest}는 아홉 글이 모두 초안이고 용어 문장이 비어 있기를 기대한다.
 */
public final class LearningSeeds {

    public static final String MAP_VERSION = "LEARNING-IT-MAP";

    private static final String CHECK_VERSION = "learning-publish-checks-v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LearningSeeds(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 공개 판·기록·색인을 지우고 글을 초안으로, 용어 문장을 빈 문자열로 되돌린다. 대응표도 지운다. */
    public void reset() {
        jdbc.update("DELETE FROM learning_published_term");
        jdbc.update("DELETE FROM learning_publication_event");
        jdbc.update("UPDATE learning_article SET status = 'DRAFT', published_revision_id = NULL");
        jdbc.update("DELETE FROM learning_article_revision");
        jdbc.update("UPDATE learning_term SET sentence = ''");
        clearSectorMap();
    }

    /**
     * 공개 계약 응답({@code article.json} 모양의 {@code data})을 판으로 되돌려 심는다. 판의 {@code term} 블록은 {@code
     * termId}만 담고 이름·문장은 {@code terms}로 옮긴다. 응답 전용 필드({@code available}·{@code name} 등)는 버린다.
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
        return publish(snapshot, publishedAt);
    }

    /**
     * 목록 항목({@code articles-list.json}의 한 줄)만으로 최소 판을 심는다. 본문은 문단 하나, 예시는 두 섹터다.
     *
     * @return 심은 판의 대리키
     */
    public long publishMinimal(String articleId, String title, String question, int readingOrder) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("articleId", articleId);
        snapshot.put("title", title);
        snapshot.put("question", question);
        snapshot.put("readingOrder", readingOrder);
        snapshot.putArray("bodyBlocks").addObject().put("type", "paragraph").put("text", "합성 문단");
        ArrayNode sectors = snapshot.putObject("example").putArray("sectors");
        sectors.addObject().put("code", "SEMICONDUCTOR").put("weightTenths", 500);
        sectors.addObject().put("code", "BATTERY").put("weightTenths", 500);
        snapshot.putArray("sources");
        snapshot.putArray("terms");
        snapshot.put("draftVersion", 0);
        return publish(snapshot, Instant.parse("2026-09-20T01:00:00Z"));
    }

    /** 판 하나를 넣고 글을 그 판으로 게시하며 판의 용어로 색인을 채운다. 게시 유스케이스가 한 트랜잭션에서 할 일과 같은 결과다. */
    private long publish(ObjectNode snapshot, Instant publishedAt) {
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
                             published_by_user_id, published_at)
                        VALUES (?, ?, CAST(? AS JSONB), ?, ?, 7, CAST(? AS TIMESTAMPTZ))
                        RETURNING id
                        """,
                        Long.class,
                        articleId,
                        lastRevisionNo == null ? 1 : lastRevisionNo + 1,
                        snapshot.toString(),
                        CHECK_VERSION,
                        MAP_VERSION,
                        publishedAt.toString());
        jdbc.update(
                "UPDATE learning_article SET status = 'PUBLISHED', published_revision_id = ?"
                        + " WHERE id = ?",
                revisionId,
                articleId);
        jdbc.update("DELETE FROM learning_published_term WHERE article_id = ?", articleId);
        for (JsonNode term : snapshot.path("terms")) {
            jdbc.update(
                    "INSERT INTO learning_published_term"
                            + " (term_key, article_id, revision_id, label, sentence)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    term.get("termId").asText(),
                    articleId,
                    revisionId,
                    term.get("label").asText(),
                    term.get("sentence").asText());
        }
        return revisionId;
    }

    /** 열린 대응표 하나를 심는다. 섹터마다 대표 ETF를 하나씩 만든다. */
    public void seedSectorMap(Map<String, String> namesByCode) {
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
            long sectorId =
                    jdbc.queryForObject(
                            "INSERT INTO sector (code, name, classification) VALUES (?, ?, 'INDUSTRY')"
                                    + " RETURNING id",
                            Long.class,
                            sector.getKey(),
                            sector.getValue());
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
        jdbc.update("DELETE FROM sector WHERE code IN ('SEMICONDUCTOR', 'BATTERY')");
    }

    private static String etfInstrumentId(int serial) {
        return String.format("9900%02d.XKRX", serial);
    }
}
