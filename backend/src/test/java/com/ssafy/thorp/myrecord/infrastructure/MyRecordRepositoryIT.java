package com.ssafy.thorp.myrecord.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.PublicResultToken;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.persistence.BacktestRequestRepositoryImpl;
import com.ssafy.thorp.backtest.infrastructure.persistence.BacktestRunRepositoryImpl;
import com.ssafy.thorp.myrecord.domain.model.MyRecord;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.myrecord.domain.model.RecordRef;
import com.ssafy.thorp.myrecord.domain.vo.AnalysisPeriod;
import com.ssafy.thorp.myrecord.domain.vo.EntryPath;
import com.ssafy.thorp.myrecord.domain.vo.RecordName;
import com.ssafy.thorp.myrecord.infrastructure.persistence.MyRecordRepositoryImpl;
import com.ssafy.thorp.myrecord.infrastructure.persistence.RegimeSearchLogWriterImpl;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import com.ssafy.thorp.support.RepositoryTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 개인 항목의 저장·복원과 스키마 불변식을 PostgreSQL에서 확인한다(spec 006 data-model §1).
 *
 * <p>여기서 보는 두 제약이 006의 규칙을 코드 밖에서 지킨다 — 종류와 참조의 짝({@code ck_my_record_ref_matches_kind})과 같은 실행에 살아
 * 있는 항목 하나({@code uk_my_record_analysis}, FR-009).
 *
 * <p><b>제약을 확인하는 검사는 한 트랜잭션에 하나씩 둔다.</b> PostgreSQL은 제약 위반이 나면 그 트랜잭션을 중단하므로, 위반 뒤에 이어 붙인 단언은 무엇을
 * 확인하든 "트랜잭션이 이미 끊겼다"로 실패한다. 저장은 {@code flush()}로 INSERT 를 그 자리에서 내보내 예외가 나는 시점을 검사가 정한다.
 */
class MyRecordRepositoryIT extends RepositoryTestSupport {

    private static final Instant SAVED_AT = Instant.parse("2026-09-20T02:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private MyRecordRepositoryImpl records;
    @Autowired private RegimeSearchLogWriterImpl regimeLogs;
    @Autowired private BacktestRunRepositoryImpl runs;
    @Autowired private BacktestRequestRepositoryImpl requests;
    @Autowired private TestEntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("세 종류가 저장되고 참조가 그대로 복원된다")
    void 세_종류가_왕복한다() {
        long owner = newUser();
        long runId = newRun();
        long requestId = newRequest(owner, runId);

        MyRecord analysis =
                records.save(
                        MyRecord.create(
                                owner,
                                new RecordRef.Analysis(requestId, runId),
                                RecordName.of("반도체 외 1 동일 비중 2026-09-20"),
                                List.of("SEMICONDUCTOR", "BANK"),
                                List.of("EQUAL_WEIGHT"),
                                new AnalysisPeriod(
                                        LocalDate.of(2019, 1, 2), LocalDate.of(2026, 8, 29)),
                                SAVED_AT));
        MyRecord bundle =
                records.save(
                        MyRecord.create(
                                owner,
                                new RecordRef.SearchBundle(newParamSearch(owner)),
                                RecordName.of("전략 2개 탐색 2026-09-20"),
                                List.of("BANK"),
                                List.of("SECTOR_MOMENTUM", "MIN_VARIANCE"),
                                new AnalysisPeriod(
                                        LocalDate.of(2019, 1, 2), LocalDate.of(2026, 8, 29)),
                                SAVED_AT));
        MyRecord regime =
                records.save(
                        MyRecord.create(
                                owner,
                                new RecordRef.RegimeSearch(newRegimeLog(owner)),
                                RecordName.of("고용환경 2026-08"),
                                List.of(),
                                List.of(),
                                null,
                                SAVED_AT));
        entityManager.flush();
        entityManager.clear();

        assertThat(analysis.recordId().value()).matches("^MR-\\d{8}-\\d{6}$");
        assertThat(records.findByRecordId(analysis.recordId().value()))
                .hasValueSatisfying(
                        found -> {
                            assertThat(found.kind()).isEqualTo(RecordKind.ANALYSIS);
                            assertThat(found.ref())
                                    .isEqualTo(new RecordRef.Analysis(requestId, runId));
                            assertThat(found.sectorCodes())
                                    .containsExactly("SEMICONDUCTOR", "BANK");
                            assertThat(found.period().start()).isEqualTo(LocalDate.of(2019, 1, 2));
                            assertThat(found.isDeleted()).isFalse();
                            assertThat(found.isCopy()).isFalse();
                        });
        assertThat(records.findByRecordId(bundle.recordId().value()))
                .hasValueSatisfying(
                        found -> {
                            assertThat(found.kind()).isEqualTo(RecordKind.SEARCH_BUNDLE);
                            assertThat(found.strategyIds())
                                    .containsExactly("SECTOR_MOMENTUM", "MIN_VARIANCE");
                        });
        assertThat(records.findByRecordId(regime.recordId().value()))
                .hasValueSatisfying(
                        found -> {
                            assertThat(found.kind()).isEqualTo(RecordKind.REGIME_SEARCH);
                            // 경제환경 항목에는 분석 기간이 없다 — 기준월이 그 자리를 대신한다.
                            assertThat(found.period()).isNull();
                        });
        assertThat(records.countActiveByOwnerAndKind(owner, RecordKind.SEARCH_BUNDLE)).isEqualTo(1);
        assertThat(records.countActiveByOwnerAndKind(owner, RecordKind.ANALYSIS)).isEqualTo(1);
    }

    @Test
    @DisplayName("참조를 둘 가진 행은 거절한다 — 목록이 읽을 원본을 고를 수 없다")
    void 참조가_둘이면_거절한다() {
        long owner = newUser();
        long runId = newRun();
        long requestId = newRequest(owner, runId);
        long paramSearchId = newParamSearch(owner);
        entityManager.flush();

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO my_record (id, record_id, owner_id, kind,
                                            analysis_request_id, analysis_run_id, param_search_id,
                                            name, sector_codes, strategy_ids, saved_at,
                                            created_at, updated_at)
                                        VALUES (nextval('my_record_seq'), 'MR-20260920-999001', ?,
                                            'ANALYSIS', ?, ?, ?, '섞인 참조', '{}', '{}', NOW(),
                                            NOW(), NOW())
                                        """,
                                        owner,
                                        requestId,
                                        runId,
                                        paramSearchId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 실행을 가리키는 살아 있는 항목은 하나다 — 같은 조건 재분석이 항목을 늘리지 않는다(FR-009)")
    void 같은_실행에_살아있는_항목은_하나다() {
        long owner = newUser();
        long runId = newRun();
        records.save(analysisOf(owner, newRequest(owner, runId), runId, false));
        entityManager.flush();

        long secondRequestId = newRequest(owner, runId);

        // TestEntityManager 의 flush 는 Spring 변환을 거치지 않아 Hibernate 예외가 그대로 나온다.
        // 제약 이름까지 단언한다 — "무언가 걸렸다"가 아니라 어느 규칙이 막았는지가 이 검사의 내용이다.
        assertThatThrownBy(
                        () -> {
                            records.save(analysisOf(owner, secondRequestId, runId, false));
                            entityManager.flush();
                        })
                .isInstanceOf(ConstraintViolationException.class)
                .extracting(thrown -> ((ConstraintViolationException) thrown).getConstraintName())
                .asString()
                .contains("uk_my_record_analysis");
    }

    @Test
    @DisplayName("사본은 같은 실행을 가리켜도 된다 — 유니크가 사본 기능을 막지 않는다(research R6)")
    void 사본은_같은_실행을_가리킬_수_있다() {
        long owner = newUser();
        long runId = newRun();
        records.save(analysisOf(owner, newRequest(owner, runId), runId, false));

        MyRecord copy = records.save(analysisOf(owner, newRequest(owner, runId), runId, true));
        entityManager.flush();

        assertThat(copy.isCopy()).isTrue();
        // 재사용 판정은 사본을 보지 않는다 — 원본 하나만 돌아온다.
        assertThat(records.findActiveByOwnerAndRun(owner, runId))
                .hasValueSatisfying(found -> assertThat(found.isCopy()).isFalse());
    }

    @Test
    @DisplayName("지운 항목 뒤에는 같은 실행으로 다시 만들 수 있다 — 삭제가 재분석을 막지 않는다")
    void 지운_항목_뒤에는_같은_실행으로_다시_만든다() {
        long owner = newUser();
        long runId = newRun();
        MyRecord first = records.save(analysisOf(owner, newRequest(owner, runId), runId, false));
        entityManager.flush();

        jdbcTemplate.update(
                "UPDATE my_record SET deleted_at = NOW() WHERE record_id = ?",
                first.recordId().value());
        entityManager.clear();

        assertThat(records.findActiveByOwnerAndRun(owner, runId)).isEmpty();
        assertThat(records.countActiveByOwnerAndKind(owner, RecordKind.ANALYSIS)).isZero();

        records.save(analysisOf(owner, newRequest(owner, runId), runId, false));
        entityManager.flush();

        assertThat(records.findActiveByOwnerAndRun(owner, runId)).isPresent();
    }

    @Test
    @DisplayName("경제환경 로그는 소유자·키·진입 경로마다 한 행이고 열람 시각만 오른다(research R15)")
    void 경제환경_로그는_경로별로_한_행이다() {
        long owner = newUser();
        String snapshotKey = "FIELD:EMPLOYMENT:2026-08-01:MACRO-1:REG-1:REGIME-V1";

        long logId =
                regimeLogs.append(
                        owner,
                        snapshotKey,
                        null,
                        EntryPath.MARKET_FLOW,
                        BaselineOrigin.T_G,
                        SAVED_AT);
        entityManager.flush();

        assertThat(regimeLogs.findActive(owner, snapshotKey, EntryPath.MARKET_FLOW))
                .hasValue(logId);
        // 진입 경로가 다르면 다른 기록이다 — 한쪽이 다른 쪽을 대체하지 않는다(Story 3 시나리오 4).
        assertThat(regimeLogs.findActive(owner, snapshotKey, EntryPath.STRATEGY_RESULT)).isEmpty();

        // 같은 검색을 다시 열어도 행이 늘지 않는다. 조회한 뒤 없으면 넣는 방식이면 동시에 두 번 열 때
        // 둘 다 "없음"을 읽어 기록이 둘이 되고, 목록에 같은 항목이 두 줄로 남는다.
        long again =
                regimeLogs.append(
                        owner,
                        snapshotKey,
                        null,
                        EntryPath.MARKET_FLOW,
                        BaselineOrigin.T_G,
                        SAVED_AT.plusSeconds(60));
        assertThat(again).isEqualTo(logId);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM regime_search_log WHERE owner_user_id = ?",
                                Integer.class,
                                owner))
                .isEqualTo(1);

        Instant later = SAVED_AT.plusSeconds(3600);
        regimeLogs.touch(logId, later);
        entityManager.flush();

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT viewed_at FROM regime_search_log WHERE id = ?",
                                Instant.class,
                                logId))
                .isEqualTo(later);
    }

    private static MyRecord analysisOf(long owner, long requestId, long runId, boolean isCopy) {
        return new MyRecord(
                null,
                null,
                owner,
                new RecordRef.Analysis(requestId, runId),
                RecordName.of(isCopy ? "사본" : "같은 실행"),
                List.of("BANK"),
                List.of("EQUAL_WEIGHT"),
                null,
                SAVED_AT,
                null,
                null,
                isCopy);
    }

    /** users FK를 만족시킬 최소 행. 회원 도메인을 끌어오지 않으려고 직접 넣는다. */
    private long newUser() {
        // phone_number 가 varchar(20) 이라 이메일을 그대로 넣을 수 없다.
        String suffix = String.format("%08d", SEQ.incrementAndGet());
        String email = "mr" + suffix + "@t.test";
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "mr" + suffix,
                "010" + suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long newRun() {
        return runs.save(
                        BacktestRun.create(
                                "EQUAL_WEIGHT",
                                "1",
                                Map.of("rebalance", "MONTH_END"),
                                List.of(new InstrumentId("091160.XKRX")),
                                new BacktestPeriod(
                                        LocalDate.of(2019, 1, 2), LocalDate.of(2026, 8, 29)),
                                new BigDecimal("10000000.00"),
                                PositionSizingMode.STRATEGY_DEFINED,
                                null,
                                null,
                                42))
                .getId();
    }

    private long newRequest(long owner, long runId) {
        return requests.save(
                        BacktestRequest.create(
                                runId,
                                owner,
                                ExecutionType.SINGLE,
                                ResultSource.NEW,
                                null,
                                PublicResultToken.generate(),
                                null))
                .getId();
    }

    /** 탐색 묶음 FK를 만족시킬 최소 행. 007 접수 경로를 끌어오지 않으려고 직접 넣는다. */
    private long newParamSearch(long owner) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO param_search (id, search_id, owner_user_id, strategy_ids,
                    instrument_ids, registry_version, search_space, search_method, selection_rule,
                    limits, reference_versions, status, counts, data_version_changed,
                    created_at, updated_at)
                VALUES (nextval('param_search_seq'),
                    'PS-20260920-' || lpad(currval('param_search_seq')::text, 6, '0'), ?,
                    '[]'::jsonb, '[]'::jsonb, 'REG-1', '{}'::jsonb, 'GRID', '{}'::jsonb,
                    '{}'::jsonb, '{}'::jsonb, 'QUEUED', '{}'::jsonb, false, NOW(), NOW())
                RETURNING id
                """,
                Long.class,
                owner);
    }

    private long newRegimeLog(long owner) {
        return regimeLogs.append(
                owner,
                "FIELD:EMPLOYMENT:2026-08-01:MACRO-1:REG-1:REGIME-V1",
                null,
                EntryPath.MARKET_FLOW,
                BaselineOrigin.T_G,
                SAVED_AT);
    }
}
