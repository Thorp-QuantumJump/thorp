package com.ssafy.thorp.myrecord;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 내 기록 이름 변경·삭제(spec 006 T047, FR-017·SC-001·SC-007, quickstart S9).
 *
 * <p>여기서 보는 것은 <b>무엇이 남는가</b>다. 이름을 바꿔도 계산 결과는 그대로여야 하고, 지워도 사라지는 것은 그 사용자의 소유 기록뿐이어야 한다 — 같은 공용
 * 결과를 가리키는 다른 사용자의 항목이 함께 사라지면 SC-007 위반이다.
 *
 * <p><b>기간을 다른 IT와 겹치지 않게 둔다.</b> PostgreSQL 컨테이너를 스위트가 공유하므로 본문이 같으면 동일 버전 키도 같아 다른 IT가 만든 실행에
 * 합류한다.
 */
class MyRecordCommandIT extends IntegrationTestSupport {

    private static final LocalDate[] PERIOD = {LocalDate.of(2024, 6, 3), LocalDate.of(2024, 9, 27)};

    @Autowired private MockMvc mockMvc;
    @Autowired private BacktestService backtestService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("이름만 바뀌고 저장된 계산 결과는 그대로다(SC-001)")
    void 이름만_바뀐다() throws Exception {
        long owner = newUser("rename");
        BacktestJobResult accepted = backtestService.create(request(owner));
        long runId = runIdOf(accepted);
        insertResult(runId);
        String before = resultDigest(runId);
        String recordId = recordIdOf(owner);

        mockMvc.perform(rename(owner, recordId, "  반도체 비중 높인 안  "))
                .andExpect(status().isOk())
                // 앞뒤 공백은 지운 뒤 저장한다.
                .andExpect(jsonPath("$.data.name").value("반도체 비중 높인 안"))
                .andExpect(jsonPath("$.data.recordId").value(recordId));

        assertThat(resultDigest(runId)).as("이름을 바꿨다고 계산 결과가 달라지면 SC-001 위반이다").isEqualTo(before);
    }

    @Test
    @DisplayName("빈 이름은 400이고 자동 이름으로 되돌리지 않는다")
    void 빈_이름은_거절한다() throws Exception {
        long owner = newUser("blank");
        backtestService.create(request(owner));
        String recordId = recordIdOf(owner);
        String before = nameOf(owner);

        mockMvc.perform(rename(owner, recordId, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        assertThat(nameOf(owner)).isEqualTo(before);
    }

    @Test
    @DisplayName("name 외의 키는 무시하지 않고 400이다")
    void 모르는_키는_거절한다() throws Exception {
        long owner = newUser("unknown");
        backtestService.create(request(owner));
        String recordId = recordIdOf(owner);

        mockMvc.perform(
                        patch("/my-records/{recordId}", recordId)
                                .with(csrf())
                                .with(as(owner))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\": \"새 이름\", \"savedAt\": \"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        assertThat(nameOf(owner)).as("조용히 이름만 바꾸고 나머지를 버리지 않는다").doesNotContain("새 이름");
    }

    @Test
    @DisplayName("삭제하면 목록·상세에서 사라지고 두 번째 삭제는 404다")
    void 삭제는_한_번뿐이다() throws Exception {
        long owner = newUser("delete");
        backtestService.create(request(owner));
        String recordId = recordIdOf(owner);

        mockMvc.perform(delete("/my-records/{recordId}", recordId).with(csrf()).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").value(recordId))
                .andExpect(jsonPath("$.data.deletedAt").exists());

        assertThat(deletedAtOf(owner)).as("행은 남고 시각만 채워진다").isNotNull();
        mockMvc.perform(get("/my-records").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        mockMvc.perform(get("/my-records/{recordId}", recordId).with(as(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(delete("/my-records/{recordId}", recordId).with(csrf()).with(as(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("지워도 같은 실행을 가리키는 남의 항목은 그대로 열린다(SC-007)")
    void 남의_기록은_함께_사라지지_않는다() throws Exception {
        long mine = newUser("sc007-a");
        long theirs = newUser("sc007-b");
        BacktestJobResult first = backtestService.create(request(mine));
        BacktestJobResult second = backtestService.create(request(theirs));
        // 같은 조건이라 실행을 공유한다 — 한쪽을 지울 때 공용 결과가 함께 사라지면 안 된다.
        long runId = runIdOf(first);
        assertThat(runIdOf(second)).isEqualTo(runId);
        insertResult(runId);

        mockMvc.perform(
                        delete("/my-records/{recordId}", recordIdOf(mine))
                                .with(csrf())
                                .with(as(mine)))
                .andExpect(status().isOk());

        String theirRecord = recordIdOf(theirs);
        mockMvc.perform(get("/my-records/{recordId}", theirRecord).with(as(theirs)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").value(theirRecord))
                // 공개 토큰이 살아 있어야 그 결과를 계속 열 수 있다.
                .andExpect(jsonPath("$.data.resultToken").value(second.publicToken()));
        assertThat(resultDigest(runId)).as("공용 계산 결과는 지우지 않는다").isNotNull();
    }

    @Test
    @DisplayName("남의 기록은 403이 아니라 404다 — 식별자의 존재를 알리지 않는다")
    void 남의_기록은_404다() throws Exception {
        long owner = newUser("owner");
        long stranger = newUser("stranger");
        backtestService.create(request(owner));
        String recordId = recordIdOf(owner);

        mockMvc.perform(rename(stranger, recordId, "남의 것"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(delete("/my-records/{recordId}", recordId).with(csrf()).with(as(stranger)))
                .andExpect(status().isNotFound());

        assertThat(deletedAtOf(owner)).as("남이 눌러도 지워지지 않는다").isNull();
    }

    @Test
    @DisplayName("비로그인은 401이다")
    void 비로그인은_거절한다() throws Exception {
        mockMvc.perform(
                        patch("/my-records/{recordId}", "MR-20260921-000001")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"이름\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/my-records/{recordId}", "MR-20260921-000001").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder rename(long owner, String recordId, String name) {
        return patch("/my-records/{recordId}", recordId)
                .with(csrf())
                .with(as(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"" + name + "\"}");
    }

    private String recordIdOf(long owner) {
        return jdbc.queryForObject(
                "SELECT record_id FROM my_record WHERE owner_id = ?", String.class, owner);
    }

    private String nameOf(long owner) {
        return jdbc.queryForObject(
                "SELECT name FROM my_record WHERE owner_id = ?", String.class, owner);
    }

    private java.sql.Timestamp deletedAtOf(long owner) {
        return jdbc.queryForObject(
                "SELECT deleted_at FROM my_record WHERE owner_id = ?",
                java.sql.Timestamp.class,
                owner);
    }

    private long runIdOf(BacktestJobResult result) {
        return jdbc.queryForObject(
                "SELECT run_id FROM backtest_request WHERE backtest_id = ?",
                Long.class,
                result.backtestId());
    }

    /** 결과 행이 그대로인지 보는 지문. 없으면 null 이라 "지워졌다"도 여기서 드러난다. */
    private String resultDigest(long runId) {
        List<String> rows =
                jdbc.queryForList(
                        "SELECT performance::text || '|' || updated_at FROM backtest_result WHERE"
                                + " run_id = ?",
                        String.class,
                        runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertResult(long runId) {
        jdbc.update(
                "INSERT INTO backtest_result (run_id, execution_id, finished_event_id, started_at,"
                        + " finished_at, portfolio_series, diagnostics, performance, created_at,"
                        + " updated_at)"
                        + " VALUES (?, ?, ?, NOW(), NOW(), '[]'::jsonb, '{}'::jsonb, ?::jsonb,"
                        + " NOW(), NOW())"
                        + " ON CONFLICT (run_id) DO NOTHING",
                runId,
                "EXEC-CMD-" + runId,
                "EVT-CMD-" + runId,
                """
                {"netTotalReturn": "0.0900", "maxDrawdown": "-0.0600", "totalTradingCost": "0.0040"}
                """);
    }

    private long newUser(String tag) {
        String suffix = tag + (System.nanoTime() % 1_000_000_000L);
        String email = "my-record-cmd-" + suffix + "@t.test";
        jdbc.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "u" + suffix,
                suffix);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static CreateBacktestCommand request(Long requesterId) {
        return new CreateBacktestCommand(
                requesterId,
                ExecutionType.SINGLE,
                "SECTOR-ETF-2026-09",
                List.of(
                        new CreateBacktestCommand.AllocationItem(
                                "SEMICONDUCTOR", "091160.XKRX", new BigDecimal("0.4000")),
                        new CreateBacktestCommand.AllocationItem(
                                "BATTERY", "305720.XKRX", new BigDecimal("0.6000"))),
                "FIXED_WEIGHT",
                "1",
                null,
                Map.of("rebalance", "MONTH_END"),
                PERIOD[0],
                PERIOD[1],
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                null,
                Scenario.FULL_PERIOD,
                null);
    }
}
