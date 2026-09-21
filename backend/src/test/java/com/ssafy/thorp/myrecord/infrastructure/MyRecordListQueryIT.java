package com.ssafy.thorp.myrecord.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.myrecord.application.command.MyRecordListQuery;
import com.ssafy.thorp.myrecord.application.command.MyRecordListQuery.RecordSort;
import com.ssafy.thorp.myrecord.application.result.MyRecordPage;
import com.ssafy.thorp.myrecord.application.result.MyRecordView;
import com.ssafy.thorp.myrecord.application.result.SourceVersions;
import com.ssafy.thorp.myrecord.domain.model.MyRecord;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.myrecord.domain.model.RecordRef;
import com.ssafy.thorp.myrecord.domain.model.RecordStatus;
import com.ssafy.thorp.myrecord.domain.vo.AnalysisPeriod;
import com.ssafy.thorp.myrecord.domain.vo.EntryPath;
import com.ssafy.thorp.myrecord.domain.vo.RecordName;
import com.ssafy.thorp.myrecord.infrastructure.persistence.MyRecordRepositoryImpl;
import com.ssafy.thorp.myrecord.infrastructure.persistence.RegimeSearchLogWriterImpl;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import com.ssafy.thorp.support.RepositoryTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 목록 질의가 종류별 원본에서 상태를 파생하고 필터·커서가 계약대로 동작하는지 PostgreSQL 에서 확인한다(spec 006 T018, contracts "GET
 * /my-records").
 *
 * <p>원본 행(실행·요청·묶음·스냅샷)은 SQL 로 직접 넣는다 — 004·007·010 접수 경로를 끌어오면 검사가 무엇을 보는지 흐려진다. 상태는 {@code
 * my_record}에 없으므로 원본 컬럼을 바꾸는 것이 곧 검사 준비다.
 */
class MyRecordListQueryIT extends RepositoryTestSupport {

    private static final Instant BASE = Instant.parse("2026-09-20T00:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String KEY = "FIELD:EMPLOYMENT:2026-08-01:FIXTURE-1:REG-1:REGIME-V1";

    @Autowired private MyRecordRepositoryImpl records;
    @Autowired private RegimeSearchLogWriterImpl regimeLogs;
    @Autowired private TestEntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("기본 보기는 완료한 분석·묶음과 문턱 적용 경제환경만 보이고 저장일 최신순이다")
    void 기본_보기는_완료_항목만() {
        long owner = newUser();
        long completedRun = newRun("COMPLETED", null);
        String token = newRequest(owner, completedRun, false);
        MyRecord completed = analysis(owner, completedRun, BASE.plusSeconds(10));
        analysis(owner, newRun("FAILED", "ENGINE_ERROR"), BASE.plusSeconds(20));
        analysis(owner, newRun("RUNNING", null), BASE.plusSeconds(30));
        long bundleId = newParamSearch(owner, "COMPLETED", null);
        MyRecord bundle = bundle(owner, bundleId, BASE.plusSeconds(40));
        bundle(owner, newParamSearch(owner, "PARTIAL", "TIME_LIMIT"), BASE.plusSeconds(50));
        MyRecord regime =
                regime(
                        owner,
                        newRegimeLog(owner, newSnapshot("THRESHOLD_APPLIED", null)),
                        BASE.plusSeconds(60));
        regime(owner, newRegimeLog(owner, null), BASE.plusSeconds(70));
        flush();

        MyRecordPage page = records.findPage(owner, query());

        assertThat(page.nextCursor()).isNull();
        assertThat(page.items())
                .extracting(MyRecordView::recordId)
                .containsExactly(
                        regime.recordId().value(),
                        bundle.recordId().value(),
                        completed.recordId().value());
        MyRecordView first = page.items().get(2);
        assertThat(first.status()).isEqualTo(RecordStatus.COMPLETED);
        assertThat(first.resultToken()).isEqualTo(token);
        assertThat(first.searchId()).isNull();
        assertThat(first.regime()).isNull();
        MyRecordView second = page.items().get(1);
        assertThat(second.kind()).isEqualTo(RecordKind.SEARCH_BUNDLE);
        assertThat(second.searchId()).startsWith("PS-");
        assertThat(second.resultToken()).isNull();
        MyRecordView third = page.items().get(0);
        assertThat(third.status()).isEqualTo(RecordStatus.THRESHOLD_APPLIED);
        assertThat(third.regime().withdrawn()).isFalse();
        assertThat(third.regime().key().toString()).isEqualTo(KEY);
        assertThat(third.regime().entryPath()).isEqualTo(EntryPath.MARKET_FLOW);
        assertThat(third.period()).isNull();
    }

    @Test
    @DisplayName("실패·취소는 상태 필터를 줘야 보이고 사유가 함께 온다. 요청을 취소했으면 실행이 끝났어도 취소다")
    void 실패_취소는_필터로만_보인다() {
        long owner = newUser();
        MyRecord failed = analysis(owner, newRun("FAILED", "ENGINE_ERROR"), BASE.plusSeconds(10));
        long cancelledRun = newRun("COMPLETED", null);
        newRequest(owner, cancelledRun, true);
        MyRecord cancelled = analysis(owner, cancelledRun, BASE.plusSeconds(20));
        MyRecord partial =
                bundle(owner, newParamSearch(owner, "PARTIAL", "TIME_LIMIT"), BASE.plusSeconds(30));
        flush();

        assertThat(records.findPage(owner, query()).items()).isEmpty();

        MyRecordPage page =
                records.findPage(
                        owner,
                        query(
                                Set.of(
                                        RecordStatus.FAILED,
                                        RecordStatus.CANCELLED,
                                        RecordStatus.PARTIAL)));

        assertThat(page.items())
                .extracting(
                        MyRecordView::recordId, MyRecordView::status, MyRecordView::statusReason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                partial.recordId().value(), RecordStatus.PARTIAL, "TIME_LIMIT"),
                        org.assertj.core.groups.Tuple.tuple(
                                cancelled.recordId().value(), RecordStatus.CANCELLED, null),
                        org.assertj.core.groups.Tuple.tuple(
                                failed.recordId().value(), RecordStatus.FAILED, "ENGINE_ERROR"));
    }

    @Test
    @DisplayName("대기 중 실행은 RUNNING 으로, 스냅샷이 사라진 경제환경 로그는 WITHDRAWN 으로 보인다")
    void 대기는_RUNNING_철회는_WITHDRAWN() {
        long owner = newUser();
        MyRecord queued = analysis(owner, newRun("QUEUED", null), BASE.plusSeconds(10));
        MyRecord withdrawn = regime(owner, newRegimeLog(owner, null), BASE.plusSeconds(20));
        flush();

        MyRecordPage page =
                records.findPage(
                        owner, query(Set.of(RecordStatus.RUNNING, RecordStatus.WITHDRAWN)));

        assertThat(page.items())
                .extracting(MyRecordView::recordId, MyRecordView::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                withdrawn.recordId().value(), RecordStatus.WITHDRAWN),
                        org.assertj.core.groups.Tuple.tuple(
                                queued.recordId().value(), RecordStatus.RUNNING));
        assertThat(page.items().get(0).regime().withdrawn()).isTrue();
    }

    @Test
    @DisplayName("섹터 필터는 하나라도 겹치면 포함이고, 종류·전략·기간 필터도 함께 건다")
    void 섹터_필터는_겹치면_포함() {
        long owner = newUser();
        MyRecord two =
                save(owner, List.of("SEMICONDUCTOR", "BANK"), List.of("EQUAL_WEIGHT"), 2019, 10);
        MyRecord bio = save(owner, List.of("BIO"), List.of("SECTOR_MOMENTUM"), 2023, 20);
        flush();

        assertThat(records.findPage(owner, sectors("BANK", "BIO")).items())
                .extracting(MyRecordView::recordId)
                .containsExactly(bio.recordId().value(), two.recordId().value());
        assertThat(records.findPage(owner, sectors("BATTERY")).items()).isEmpty();

        MyRecordListQuery momentum =
                new MyRecordListQuery(
                        Set.of(RecordKind.ANALYSIS),
                        null,
                        null,
                        List.of("SECTOR_MOMENTUM"),
                        null,
                        null,
                        null,
                        null,
                        20);
        assertThat(records.findPage(owner, momentum).items())
                .extracting(MyRecordView::recordId)
                .containsExactly(bio.recordId().value());

        // 2019~2026 은 2020 구간과 겹치고 2023~2026 은 겹치지 않는다.
        MyRecordListQuery in2020 =
                new MyRecordListQuery(
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2020, 1, 1),
                        LocalDate.of(2020, 12, 31),
                        null,
                        null,
                        20);
        assertThat(records.findPage(owner, in2020).items())
                .extracting(MyRecordView::recordId)
                .containsExactly(two.recordId().value());
    }

    @Test
    @DisplayName("커서로 넘긴 다음 쪽은 사이에 새 항목이 들어와도 겹치거나 빠지지 않는다")
    void 커서는_새_항목이_끼어도_안정적() {
        long owner = newUser();
        for (int i = 1; i <= 5; i++) {
            analysis(owner, newRun("COMPLETED", null), BASE.plusSeconds(i * 10));
        }
        flush();

        MyRecordPage first = records.findPage(owner, sized(2, null));
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();

        // 첫 쪽을 본 뒤 가장 최신 항목이 새로 들어온다. offset 이면 두 번째 쪽이 한 항목을 다시 보인다.
        analysis(owner, newRun("COMPLETED", null), BASE.plusSeconds(600));
        flush();

        MyRecordPage second = records.findPage(owner, sized(2, first.nextCursor()));
        MyRecordPage third = records.findPage(owner, sized(2, second.nextCursor()));

        assertThat(third.nextCursor()).isNull();
        List<String> seen =
                java.util.stream.Stream.of(first, second, third)
                        .flatMap(page -> page.items().stream())
                        .map(MyRecordView::recordId)
                        .toList();
        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        assertThat(seen).isSortedAccordingTo(java.util.Comparator.reverseOrder());

        // 이름 정렬도 같은 자리를 잡는다. 커서 문자열은 불투명하게 오간다.
        MyRecordListQuery byName =
                new MyRecordListQuery(
                        null, null, null, null, null, null, RecordSort.NAME_ASC, null, 3);
        MyRecordPage names = records.findPage(owner, byName);
        MyRecordPage moreNames =
                records.findPage(
                        owner,
                        new MyRecordListQuery(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                RecordSort.NAME_ASC,
                                MyRecordListQuery.Cursor.parse(names.nextCursor().encode()),
                                3));
        assertThat(moreNames.items()).hasSize(3);
        assertThat(moreNames.nextCursor()).isNull();
    }

    @Test
    @DisplayName("다른 사용자의 항목은 목록에도 상세에도 없다. 지운 항목도 같다")
    void 남의_항목은_보이지_않는다() {
        long owner = newUser();
        long other = newUser();
        MyRecord mine = analysis(owner, newRun("COMPLETED", null), BASE.plusSeconds(10));
        MyRecord theirs = analysis(other, newRun("COMPLETED", null), BASE.plusSeconds(20));
        flush();

        assertThat(records.findPage(owner, query()).items())
                .extracting(MyRecordView::recordId)
                .containsExactly(mine.recordId().value());
        assertThat(records.findView(owner, theirs.recordId().value())).isEmpty();
        assertThat(records.findView(owner, mine.recordId().value())).isPresent();

        jdbcTemplate.update(
                "UPDATE my_record SET deleted_at = NOW() WHERE record_id = ?",
                mine.recordId().value());
        assertThat(records.findView(owner, mine.recordId().value())).isEmpty();
    }

    @Test
    @DisplayName("참조 버전 원문을 원본에서 읽고, 열람 표시는 그 컬럼만 바꾼다")
    void 참조_버전과_열람_표시() {
        long owner = newUser();
        long runId = newRun("COMPLETED", null);
        MyRecord record = analysis(owner, runId, BASE);
        long bundleId = newParamSearch(owner, "COMPLETED", null);
        MyRecord bundle = bundle(owner, bundleId, BASE.plusSeconds(1));
        flush();

        SourceVersions analysis =
                records.findSourceVersions(record.id(), RecordKind.ANALYSIS).orElseThrow();
        assertThat(analysis.versions().get("mapping")).isEqualTo("SECTOR-ETF-2026-09");
        assertThat(analysis.versions().get("rules"))
                .isEqualTo(java.util.Map.of("version", "RULES-1", "provisional", true));
        assertThat(analysis.spec().get("registryVersion")).isEqualTo("REG-1");

        SourceVersions search =
                records.findSourceVersions(bundle.id(), RecordKind.SEARCH_BUNDLE).orElseThrow();
        assertThat(search.versions().get("marketData")).isEqualTo("FIXTURE-1");
        assertThat(search.spec().get("mappingVersion")).isEqualTo("SECTOR-ETF-2026-09");

        Instant viewedAt = BASE.plusSeconds(3600);
        records.markViewed(record.id(), viewedAt);
        MyRecordView view = records.findView(owner, record.recordId().value()).orElseThrow();
        assertThat(view.lastViewedAt()).isEqualTo(viewedAt);
        assertThat(view.name()).isEqualTo(record.name().value());
    }

    // --- 질의 ---------------------------------------------------------------

    private static MyRecordListQuery query() {
        return query(null);
    }

    private static MyRecordListQuery query(Set<RecordStatus> statuses) {
        return new MyRecordListQuery(null, statuses, null, null, null, null, null, null, 20);
    }

    private static MyRecordListQuery sectors(String... codes) {
        return new MyRecordListQuery(null, null, List.of(codes), null, null, null, null, null, 20);
    }

    private static MyRecordListQuery sized(int size, MyRecordListQuery.Cursor cursor) {
        return new MyRecordListQuery(null, null, null, null, null, null, null, cursor, size);
    }

    // --- 기록 ---------------------------------------------------------------

    private MyRecord analysis(long owner, long runId, Instant savedAt) {
        return records.save(
                MyRecord.create(
                        owner,
                        new RecordRef.Analysis(runId),
                        RecordName.of("분석 " + SEQ.incrementAndGet()),
                        List.of("SEMICONDUCTOR", "BATTERY"),
                        List.of("EQUAL_WEIGHT"),
                        new AnalysisPeriod(LocalDate.of(2019, 1, 2), LocalDate.of(2026, 8, 29)),
                        savedAt));
    }

    private MyRecord save(
            long owner, List<String> sectors, List<String> strategies, int startYear, int offset) {
        return records.save(
                MyRecord.create(
                        owner,
                        new RecordRef.Analysis(newRun("COMPLETED", null)),
                        RecordName.of("분석 " + SEQ.incrementAndGet()),
                        sectors,
                        strategies,
                        new AnalysisPeriod(
                                LocalDate.of(startYear, 1, 2), LocalDate.of(2026, 8, 29)),
                        BASE.plusSeconds(offset)));
    }

    private MyRecord bundle(long owner, long paramSearchId, Instant savedAt) {
        return records.save(
                MyRecord.create(
                        owner,
                        new RecordRef.SearchBundle(paramSearchId),
                        RecordName.of("탐색 " + SEQ.incrementAndGet()),
                        List.of("BANK"),
                        List.of("SECTOR_MOMENTUM", "MIN_VARIANCE"),
                        new AnalysisPeriod(LocalDate.of(2019, 1, 2), LocalDate.of(2026, 8, 29)),
                        savedAt));
    }

    private MyRecord regime(long owner, long logId, Instant savedAt) {
        return records.save(
                MyRecord.create(
                        owner,
                        new RecordRef.RegimeSearch(logId),
                        RecordName.of("고용환경 " + SEQ.incrementAndGet()),
                        List.of(),
                        List.of(),
                        null,
                        savedAt));
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    // --- 원본 행 -------------------------------------------------------------

    private long newUser() {
        String suffix = String.format("%08d", SEQ.incrementAndGet());
        String email = "ml" + suffix + "@t.test";
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "ml" + suffix,
                "011" + suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /** 접수가 확정한 참조 버전을 {@code versions}·{@code request_spec}에 함께 넣는다 — 실패한 실행에도 있는 값이다. */
    private long newRun(String status, String failureCode) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO backtest_run (id, backtest_id, strategy_id, strategy_version,
                    parameters, instrument_ids, start_date, end_date, initial_cash,
                    position_sizing_type, random_seed, status, failure_code, versions,
                    request_spec, created_at, updated_at)
                VALUES (nextval('backtest_run_seq'),
                    'BT-LIST-' || lpad(currval('backtest_run_seq')::text, 6, '0'),
                    'EQUAL_WEIGHT', '1', '{}'::jsonb, '["091160.XKRX"]'::jsonb,
                    DATE '2019-01-02', DATE '2026-08-29', 10000000.00, 'STRATEGY_DEFINED', 42,
                    ?, ?,
                    '{"mapping": "SECTOR-ETF-2026-09", "data": "FIXTURE-1",
                      "rules": {"version": "RULES-1", "provisional": true},
                      "costProfile": {"version": "CP-1", "provisional": false},
                      "engine": "worker-0.9", "worker": "must-not-leak"}'::jsonb,
                    '{"registryVersion": "REG-1"}'::jsonb,
                    NOW(), NOW())
                RETURNING id
                """,
                Long.class,
                status,
                failureCode);
    }

    /** 단건 요청 하나. 공개 토큰을 돌려준다 — 목록의 {@code resultToken}이 이 값이어야 한다. */
    private String newRequest(long owner, long runId, boolean cancelled) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO backtest_request (id, backtest_id, run_id, requester_id,
                    execution_type, result_source, public_token, cancelled_at,
                    created_at, updated_at)
                VALUES (nextval('backtest_request_seq'),
                    'BT-LISTREQ-' || lpad(currval('backtest_request_seq')::text, 6, '0'),
                    ?, ?, 'SINGLE', 'NEW',
                    lpad(to_hex(currval('backtest_request_seq')), 32, '0'),
                    CASE WHEN ? THEN NOW() ELSE NULL END, NOW(), NOW())
                RETURNING public_token
                """,
                String.class,
                runId,
                owner,
                cancelled);
    }

    private long newParamSearch(long owner, String status, String stopReason) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO param_search (id, search_id, owner_user_id, strategy_ids,
                    instrument_ids, registry_version, search_space, search_method, selection_rule,
                    limits, reference_versions, execution_plan, status, stop_reason, counts,
                    data_version_changed, created_at, updated_at)
                VALUES (nextval('param_search_seq'),
                    'PS-20260920-' || lpad(currval('param_search_seq')::text, 6, '0'), ?,
                    '[]'::jsonb, '[]'::jsonb, 'REG-1', '{}'::jsonb, 'GRID', '{}'::jsonb,
                    '{}'::jsonb,
                    '{"marketData": "FIXTURE-1", "registry": "REG-1", "cost": "CP-1",
                      "engine": "worker-0.9"}'::jsonb,
                    '{"mappingVersion": "SECTOR-ETF-2026-09"}'::jsonb,
                    ?, ?, '{}'::jsonb, false, NOW(), NOW())
                RETURNING id
                """,
                Long.class,
                owner,
                status,
                stopReason);
    }

    private long newSnapshot(String status, String statusReason) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO regime_search_snapshot (
                    snapshot_key, mode, field_id, baseline_month, revision, content_hash,
                    data_version, registry_version, model_version, status, status_reason,
                    scale, comparison_months, ranked_candidates, candidate_counts, data_asof,
                    computed_at, created_at, updated_at)
                VALUES (?, 'FIELD', 'EMPLOYMENT', DATE '2026-08-01', 1, ?,
                    'FIXTURE-1', 'REG-1', 'REGIME-V1', ?, ?,
                    '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, '{}'::jsonb, '{}'::jsonb,
                    now(), now(), now())
                RETURNING id
                """,
                Long.class,
                KEY,
                String.format("%064d", SEQ.incrementAndGet()),
                status,
                statusReason);
    }

    /** 같은 소유자·키·진입 경로는 한 행이라(uk_regime_log_owner_key_path) 철회 행은 다른 경로로 만든다. */
    private long newRegimeLog(long owner, Long snapshotId) {
        return regimeLogs.append(
                owner,
                KEY,
                snapshotId,
                snapshotId == null ? EntryPath.STRATEGY_RESULT : EntryPath.MARKET_FLOW,
                BaselineOrigin.T_G,
                BASE);
    }
}
