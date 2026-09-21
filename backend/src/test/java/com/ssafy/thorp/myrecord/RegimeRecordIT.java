package com.ssafy.thorp.myrecord;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.myrecord.application.result.RegimeRecordOutcome;
import com.ssafy.thorp.myrecord.application.service.MyRecordRegistrar;
import com.ssafy.thorp.myrecord.domain.vo.EntryPath;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import com.ssafy.thorp.support.IntegrationTestSupport;
import com.ssafy.thorp.support.TestUsers;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
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
 * 경제환경 검색 기록 API(spec 006 T036, contracts "POST /my-records/regime-searches", quickstart S7-4).
 *
 * <p>스냅샷 행은 {@code data/} 배치가 넣는 것이므로 여기서는 {@code RegimeSearchApiIT}가 하듯 같은 모양을 직접 심는다. 이 시험이 보는 것은
 * 그 위에서 <b>006이 무엇을 남기는가</b>다 — 진입 경로마다 별도 항목인지, 다시 열었을 때 항목이 늘지 않는지, 기준월 근거가 요청이 보낸 값 그대로인지.
 */
@DisplayName("경제환경 검색 기록")
class RegimeRecordIT extends IntegrationTestSupport {

    private static final String KEY =
            "FIELD:LABOR:2026-07-01:FIXTURE-20260915:FIXTURE-REG-1:REGIME-V1";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MyRecordRegistrar registrar;

    private long owner;

    @BeforeEach
    void seed() {
        clean();
        owner = TestUsers.ensure(jdbc, 96_001L);
        snapshot(KEY, "LABOR", "2026-07-01");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM my_record");
        jdbc.update("DELETE FROM regime_search_log");
        jdbc.update("DELETE FROM regime_search_snapshot WHERE snapshot_key LIKE 'FIELD:LABOR:%'");
    }

    @Test
    @DisplayName("로그인 요청은 로그 행과 기록 항목을 하나씩 만들고 보낸 기준월 근거를 그대로 저장한다")
    void createsOneLogAndOneRecord() throws Exception {
        mockMvc.perform(record(KEY, "MARKET_FLOW", "T_G"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.recordId").value(Matchers.startsWith("MR-")))
                .andExpect(jsonPath("$.data.kind").value("REGIME_SEARCH"))
                .andExpect(jsonPath("$.data.regime.snapshotKey").value(KEY))
                .andExpect(jsonPath("$.data.regime.entryPath").value("MARKET_FLOW"))
                .andExpect(jsonPath("$.data.regime.baselineOrigin").value("T_G"));

        assertThat(logCount()).isEqualTo(1);
        assertThat(recordCount()).isEqualTo(1);
        // 서버가 추정한 값이 아니라 화면이 보낸 값이다(research R15).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT baseline_origin FROM regime_search_log WHERE"
                                        + " owner_user_id = ?",
                                String.class,
                                owner))
                .isEqualTo("T_G");
    }

    @Test
    @DisplayName("기준월 근거가 빠지면 400이다 — 서버는 추정하지 않는다")
    void missingBaselineOriginIsRejected() throws Exception {
        String body =
                """
                { "searchId": "%s", "entryPath": "MARKET_FLOW" }
                """
                        .formatted(KEY);

        mockMvc.perform(
                        post("/my-records/regime-searches")
                                .with(csrf())
                                .with(as(owner))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        assertThat(logCount()).isZero();
        assertThat(recordCount()).isZero();
    }

    @Test
    @DisplayName("모르는 진입 경로·기준월 근거는 400이다 — 서버가 골라 주지 않는다")
    void unknownEnumValueIsRejected() throws Exception {
        for (String body :
                List.of(body(KEY, "SOMEWHERE_ELSE", "T_G"), body(KEY, "MARKET_FLOW", "GUESSED"))) {
            mockMvc.perform(
                            post("/my-records/regime-searches")
                                    .with(csrf())
                                    .with(as(owner))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        }

        assertThat(logCount()).isZero();
        assertThat(recordCount()).isZero();
    }

    /**
     * 같은 검색을 동시에 두 번 열어도 쌍이 하나다(부분 유니크 인덱스 {@code uk_my_record_regime}).
     *
     * <p>둘 다 "없다"를 읽고 둘 다 INSERT 하면 한쪽이 제약 위반으로 끝난다. 그러지 않는 것은 {@code append}의 {@code INSERT ... ON
     * CONFLICT DO UPDATE}가 충돌한 로그 행을 <b>잠그기</b> 때문이다 — 뒤엣것은 앞엣것이 커밋할 때까지 거기서 기다리고, 풀린 뒤에는 앞엣것이 만든
     * 항목을 보게 된다. 그 순서에 기대고 있으므로 여기서 고정한다.
     */
    @Test
    @DisplayName("같은 검색을 동시에 두 번 열어도 로그 행과 항목이 하나씩이다")
    void concurrentRecordingCreatesOnePair() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        RegimeRecordOutcome one;
        RegimeRecordOutcome two;
        try {
            Callable<RegimeRecordOutcome> open =
                    () -> {
                        go.await();
                        return registrar.recordRegimeSearch(
                                owner, KEY, EntryPath.MARKET_FLOW, BaselineOrigin.T_G);
                    };
            Future<RegimeRecordOutcome> a = pool.submit(open);
            Future<RegimeRecordOutcome> b = pool.submit(open);
            go.countDown();
            one = a.get(30, TimeUnit.SECONDS);
            two = b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(one.recordId()).isEqualTo(two.recordId());
        assertThat(List.of(one.created(), two.created()))
                .as("하나는 만들었고 하나는 이미 있던 것을 돌려준다 — 둘 다 201이면 항목이 둘이다")
                .containsExactlyInAnyOrder(true, false);
        assertThat(logCount()).isEqualTo(1);
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("진입 경로가 다르면 두 번째 쌍이 생긴다 — 한쪽이 다른 쪽을 대체하지 않는다")
    void otherEntryPathMakesASecondPair() throws Exception {
        mockMvc.perform(record(KEY, "MARKET_FLOW", "T_G")).andExpect(status().isCreated());

        mockMvc.perform(record(KEY, "STRATEGY_RESULT", "T_G"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.regime.entryPath").value("STRATEGY_RESULT"));

        assertThat(logCount()).isEqualTo(2);
        assertThat(recordCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 검색을 다시 열면 200이고 열람 시각만 올라간다")
    void repeatOnlyBumpsViewedAt() throws Exception {
        mockMvc.perform(record(KEY, "MARKET_FLOW", "T_G")).andExpect(status().isCreated());
        String recordId = onlyRecordId();
        // 두 번째 요청의 시각이 확실히 더 늦도록 첫 열람을 과거로 내려 둔다. 같은 밀리초에 들어오면
        // GREATEST 가 값을 그대로 두어 "올랐는가"를 볼 수 없다.
        jdbc.update("UPDATE regime_search_log SET viewed_at = viewed_at - INTERVAL '1 hour'");
        Timestamp before = viewedAt();

        mockMvc.perform(record(KEY, "MARKET_FLOW", "T_G"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").value(recordId));

        assertThat(logCount()).isEqualTo(1);
        assertThat(recordCount()).isEqualTo(1);
        assertThat(viewedAt()).isAfter(before);
    }

    @Test
    @DisplayName("없는 스냅샷 키는 404다")
    void unknownSnapshotIsNotFound() throws Exception {
        mockMvc.perform(
                        record(
                                "FIELD:LABOR:2000-01-01:FIXTURE-20260915:FIXTURE-REG-1:REGIME-V1",
                                "MARKET_FLOW",
                                "T_G"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(logCount()).isZero();
    }

    @Test
    @DisplayName("비로그인 요청은 401이고 행이 생기지 않는다(010 FR-032)")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(
                        post("/my-records/regime-searches")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(KEY, "MARKET_FLOW", "T_G")))
                .andExpect(status().isUnauthorized());

        assertThat(logCount()).isZero();
        assertThat(recordCount()).isZero();
    }

    private MockHttpServletRequestBuilder record(
            String searchId, String entryPath, String baselineOrigin) {
        return post("/my-records/regime-searches")
                .with(csrf())
                .with(as(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(searchId, entryPath, baselineOrigin));
    }

    private static String body(String searchId, String entryPath, String baselineOrigin) {
        return """
               { "searchId": "%s", "entryPath": "%s", "baselineOrigin": "%s" }
               """
                .formatted(searchId, entryPath, baselineOrigin);
    }

    private int logCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM regime_search_log WHERE owner_user_id = ?",
                Integer.class,
                owner);
    }

    private int recordCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM my_record WHERE owner_id = ? AND deleted_at IS NULL",
                Integer.class,
                owner);
    }

    private String onlyRecordId() {
        return jdbc.queryForObject(
                "SELECT record_id FROM my_record WHERE owner_id = ?", String.class, owner);
    }

    private Timestamp viewedAt() {
        return jdbc.queryForObject(
                "SELECT viewed_at FROM regime_search_log WHERE owner_user_id = ?",
                Timestamp.class,
                owner);
    }

    /** 배치가 쓰는 것과 같은 모양의 행 하나. 사례는 없어도 되고 이 시험은 계산 결과를 보지 않는다. */
    private void snapshot(String key, String fieldId, String month) {
        jdbc.update(
                """
                INSERT INTO regime_search_snapshot (
                    snapshot_key, mode, field_id, baseline_month, revision, content_hash,
                    data_version, registry_version, model_version, status, status_reason,
                    scale, comparison_months, ranked_candidates, candidate_counts, data_asof,
                    computed_at, created_at, updated_at)
                VALUES (?, 'FIELD', ?, ?::date, 1, ?, 'FIXTURE-20260915', 'FIXTURE-REG-1',
                    'REGIME-V1', 'NOT_COMPUTABLE', 'NO_CANDIDATE', '{}'::jsonb, '{}'::jsonb,
                    '[]'::jsonb, ?::jsonb, ?::jsonb, now(), now(), now())
                """,
                key,
                fieldId,
                month,
                "r".repeat(64),
                """
                {"evaluated": 0, "excluded": {}}
                """,
                """
                {"indicators": {}, "newerReleases": []}
                """);
    }
}
