package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
import com.ssafy.thorp.support.RepositoryTestSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 계산할 수 있는 규칙 행(REGISTERED·SUPERSEDED)마다 명세 스냅샷 파일이 있고 값이 같은가(spec 004 T029).
 *
 * <p>스냅샷은 {@code docs/specs/backtest-messaging/examples/rules/{전략}-{버전}.json}이다. 워커의 버전별 골든
 * 테스트({@code worker/tests/test_rules_goldens.py})가 <b>같은 파일</b>을 규칙 사본으로 실어 엔진을 돌린다. 그래서 이 테스트가 DB
 * 와 파일을 묶고 워커 테스트가 파일과 결과를 묶으면, 등록표의 버전 하나가 결과 하나로 고정된다.
 *
 * <p><b>이 테스트가 막는 사고는 "등록된 버전을 제자리에서 고치는 것"이다.</b> 마이그레이션이 옛 행의 명세를 고치면 여기서 깨지고, 파일까지 고치면 워커의 그 버전
 * 골든이 깨진다. 규칙을 바꾸려면 새 버전 행을 더하고 파일·골든을 새로 만든다 — 옛 파일과 옛 골든은 그대로 남는다.
 *
 * <p>상태는 파일에 두지 않는다. 버전이 대체될 때 바뀌는 것은 상태뿐이고 명세는 그대로여야 하기 때문이다.
 */
class StrategyRulesSnapshotTest extends RepositoryTestSupport {

    private static final Path RULES = MessageFixtures.DIR.resolve("rules");

    /** 숫자 표기가 달라도(1e-9 · 0.000000001) 같은 값이면 같다고 본다. jsonb 는 원문 표기를 보존하지 않는다. */
    private static final Comparator<JsonNode> SAME_VALUE =
            (left, right) -> {
                if (left.isNumber() && right.isNumber()) {
                    return left.decimalValue().compareTo(right.decimalValue());
                }
                return left.equals(right) ? 0 : 1;
            };

    private final ObjectMapper json = new ObjectMapper();

    @Autowired private JdbcTemplate jdbcTemplate;

    private Map<String, JsonNode> executableRows() {
        Map<String, JsonNode> rows = new TreeMap<>();
        jdbcTemplate.query(
                "SELECT strategy_id, rules_version, provisional, spec::text AS spec"
                        + " FROM strategy_rules WHERE status IN ('REGISTERED', 'SUPERSEDED')",
                rs -> {
                    ObjectNode row = json.createObjectNode();
                    row.put("strategyId", rs.getString("strategy_id"));
                    row.put("rulesVersion", rs.getString("rules_version"));
                    row.put("provisional", rs.getBoolean("provisional"));
                    row.set("spec", read(rs.getString("spec")));
                    rows.put(
                            fileName(rs.getString("strategy_id"), rs.getString("rules_version")),
                            row);
                });
        return rows;
    }

    private Map<String, JsonNode> snapshotFiles() {
        Map<String, JsonNode> files = new TreeMap<>();
        try (Stream<Path> paths = Files.list(RULES)) {
            List<Path> jsonFiles =
                    paths.filter(path -> path.toString().endsWith(".json")).sorted().toList();
            for (Path path : jsonFiles) {
                files.put(path.getFileName().toString(), read(Files.readString(path)));
            }
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return files;
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    private static String fileName(String strategyId, String rulesVersion) {
        return strategyId + "-" + rulesVersion + ".json";
    }

    @Test
    @DisplayName("계산할 수 있는 규칙 행과 스냅샷 파일이 일대일로 있다 — 새 버전을 넣으면 파일도 함께 더한다")
    void everyExecutableRowHasASnapshotAndViceVersa() {
        assertThat(snapshotFiles().keySet())
                .as("docs/specs/backtest-messaging/examples/rules/ 의 파일 목록")
                .containsExactlyInAnyOrderElementsOf(executableRows().keySet());
    }

    @Test
    @DisplayName("스냅샷의 명세·임시 표시가 등록표 행과 같다 — 등록된 버전을 제자리에서 고치지 않았다")
    void snapshotsMatchTheRegisteredSpec() {
        Map<String, JsonNode> files = snapshotFiles();
        executableRows()
                .forEach(
                        (name, row) -> {
                            JsonNode file = files.get(name);
                            assertThat(file).as(name).isNotNull();
                            assertThat(file.equals(SAME_VALUE, row))
                                    .as("%s%n  파일: %s%n  행: %s", name, file, row)
                                    .isTrue();
                        });
    }
}
