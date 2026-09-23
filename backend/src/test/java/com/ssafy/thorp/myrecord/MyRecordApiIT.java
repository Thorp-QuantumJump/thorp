package com.ssafy.thorp.myrecord;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.support.IntegrationTestSupport;
import com.ssafy.thorp.support.Requesters;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 내 기록 API 의 경계를 HTTP 로 확인한다(spec 006 contracts 공통·오류, quickstart S12·S13).
 *
 * <p>목록·필터의 세부는 {@code MyRecordListQueryIT}가 본다. 여기서 보는 것은 컨트롤러와 보안 설정이 붙은 뒤의 응답 — 401·400·404 와 실제
 * 접수가 남긴 항목의 상세.
 *
 * <p><b>기간을 다른 IT와 겹치지 않게 둔다.</b> 본문이 같으면 동일 버전 키도 같아 다른 IT가 만든 실행에 합류한다.
 */
class MyRecordApiIT extends IntegrationTestSupport {

    private static final LocalDate[] PERIOD_A = {
        LocalDate.of(2024, 3, 4), LocalDate.of(2024, 11, 29)
    };
    private static final LocalDate[] PERIOD_B = {
        LocalDate.of(2024, 3, 5), LocalDate.of(2024, 11, 28)
    };

    @Autowired private MockMvc mockMvc;
    @Autowired private BacktestService backtestService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("비로그인은 401 이다 — 기록은 식별된 사용자 단위로만 있다(FR-020)")
    void 비로그인은_401() throws Exception {
        mockMvc.perform(get("/my-records"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("허용하지 않는 정렬·상태·크기는 400 이다. 조용히 기본값으로 바꾸지 않는다")
    void 허용하지_않는_값은_400() throws Exception {
        long owner = newUser("bad");

        // 성과 정렬은 선택지에 없다(원칙 III, research R7).
        mockMvc.perform(get("/my-records").param("sort", "RETURN_DESC").with(Requesters.as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/my-records").param("status", "DONE").with(Requesters.as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/my-records").param("size", "0").with(Requesters.as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/my-records").param("size", "51").with(Requesters.as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/my-records").param("size", "50").with(Requesters.as(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(
                        get("/my-records")
                                .param("cursor", "not-a-cursor")
                                .with(Requesters.as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("접수가 남긴 항목이 목록과 상세에 보이고, 상세에는 참조 버전과 대응표 판정이 있다")
    void 접수한_항목의_목록과_상세() throws Exception {
        long owner = newUser("mine");
        BacktestJobResult accepted = backtestService.create(request(owner, PERIOD_A));
        // 대기 중인 실행은 기본 보기에 없다(RUNNING). 끝난 것으로 바꿔 기본 보기로 확인한다.
        jdbcTemplate.update(
                "UPDATE backtest_run SET status = 'COMPLETED' WHERE id ="
                        + " (SELECT run_id FROM backtest_request WHERE backtest_id = ?)",
                accepted.backtestId());

        mockMvc.perform(get("/my-records").with(Requesters.as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].kind").value("ANALYSIS"))
                .andExpect(jsonPath("$.data.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[0].resultToken").value(accepted.publicToken()))
                .andExpect(
                        jsonPath("$.data.items[0].sectorCodes")
                                .value(Matchers.containsInAnyOrder("SEMICONDUCTOR", "BATTERY")))
                .andExpect(jsonPath("$.data.items[0].searchId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].regime").doesNotExist())
                // 성과 수치는 목록 응답에 없다(S13-5).
                .andExpect(jsonPath("$.data.items[0].totalReturn").doesNotExist())
                .andExpect(jsonPath("$.data.nextCursor").value(Matchers.nullValue()));

        String recordId =
                jdbcTemplate.queryForObject(
                        "SELECT record_id FROM my_record WHERE owner_id = ?", String.class, owner);
        mockMvc.perform(get("/my-records/{id}", recordId).with(Requesters.as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").value(recordId))
                .andExpect(jsonPath("$.data.lastViewedAt").isNotEmpty())
                .andExpect(
                        jsonPath("$.data.referenceVersions.sectorEtfMapVersion")
                                .value("SECTOR-ETF-2026-09"))
                // 등록표 버전을 주지 않은 접수다. 없으면 키를 만들지 않는다 — 화면이 "기록되지 않음"을 보인다(원칙 VI).
                .andExpect(jsonPath("$.data.referenceVersions.registryVersion").doesNotExist())
                .andExpect(jsonPath("$.data.referenceVersions.rulesVersion.version").isNotEmpty())
                .andExpect(jsonPath("$.data.referenceVersions.worker").doesNotExist())
                // 저장 당시 대응표가 지금 열린 대응표 그대로다 — 바뀐 것이 없다.
                .andExpect(jsonPath("$.data.mappingDrift.changed").value(false));

        // 저장 당시 대응표 버전이 지금 없으면 판정할 근거가 없다. changed:false 가 아니라 키가 없어야 한다(원칙 VI).
        jdbcTemplate.update(
                "UPDATE backtest_run SET versions = jsonb_set(versions, '{mapping}',"
                        + " '\"SECTOR-ETF-없음\"') WHERE id ="
                        + " (SELECT run_id FROM backtest_request WHERE backtest_id = ?)",
                accepted.backtestId());
        mockMvc.perform(get("/my-records/{id}", recordId).with(Requesters.as(owner)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.referenceVersions.sectorEtfMapVersion")
                                .value("SECTOR-ETF-없음"))
                .andExpect(jsonPath("$.data.mappingDrift").doesNotExist());
    }

    @Test
    @DisplayName("남의 기록은 403이 아니라 404다 — 존재를 알리지 않는다")
    void 남의_기록은_404() throws Exception {
        long owner = newUser("owner");
        long other = newUser("other");
        backtestService.create(request(owner, PERIOD_B));
        String recordId =
                jdbcTemplate.queryForObject(
                        "SELECT record_id FROM my_record WHERE owner_id = ?", String.class, owner);

        mockMvc.perform(get("/my-records/{id}", recordId).with(Requesters.as(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/my-records/{id}", "MR-20260101-000000").with(Requesters.as(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/my-records").with(Requesters.as(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", Matchers.empty()));
    }

    private long newUser(String tag) {
        String suffix = tag + (System.nanoTime() % 1_000_000_000L);
        String email = "my-record-api-" + suffix + "@t.test";
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "a" + suffix,
                suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static CreateBacktestCommand request(Long requesterId, LocalDate[] period) {
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
                period[0],
                period[1],
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                null,
                Scenario.FULL_PERIOD,
                null);
    }
}
