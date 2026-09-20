package com.ssafy.thorp.myrecord.infrastructure.persistence;

import com.ssafy.thorp.myrecord.application.port.RegimeSearchLogWriter;
import com.ssafy.thorp.myrecord.domain.vo.EntryPath;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link RegimeSearchLogWriter}의 구현. {@code regime_search_log}에 행을 만들고 열람 시각을 올린다.
 *
 * <p><b>010의 엔티티를 매핑하지 않고 SQL로 쓴다.</b> 표의 소유자는 010이고 그쪽은 읽기만 한다(FR-032). 엔티티에 생성 통로를 뚫으면 010 코드가
 * 006의 사정으로 바뀌는데, 006이 필요한 것은 INSERT 한 줄이라 그럴 값어치가 없다. 컬럼 계약은 010의 마이그레이션이다.
 *
 * <p><b>넣기와 찾기를 한 문장으로 한다.</b> 조회한 뒤 없으면 넣는 방식은 같은 검색을 동시에 두 번 열면 둘 다 "없음"을 읽어 기록이 둘이 된다. 그러면 목록에
 * 같은 항목이 두 줄로 남는다 — {@code uk_my_record_regime}은 서로 다른 로그 행을 가리키는 두 항목을 막지 못한다.
 */
@Repository
public class RegimeSearchLogWriterImpl implements RegimeSearchLogWriter {

    private final JdbcTemplate jdbcTemplate;

    public RegimeSearchLogWriterImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Long> findActive(long ownerId, String snapshotKey, EntryPath entryPath) {
        return jdbcTemplate
                .query(
                        """
                        SELECT id FROM regime_search_log
                        WHERE owner_user_id = ? AND snapshot_key = ? AND entry_path = ?
                        """,
                        (rs, rowNum) -> rs.getLong("id"),
                        ownerId,
                        snapshotKey,
                        entryPath.name())
                .stream()
                .findFirst();
    }

    /**
     * 이미 있으면 열람 시각만 올리고 같은 행의 id를 돌려준다.
     *
     * <p>{@code snapshotId}는 덮어쓰지 않는다 — 처음 본 revision 을 고정하는 값이라 나중 조회가 그것을 바꾸면 "무엇을 봤는지"가 흔들린다.
     * 철회로 NULL 이 된 것도 그대로 둔다.
     */
    @Override
    public long append(
            long ownerId,
            String snapshotKey,
            Long snapshotId,
            EntryPath entryPath,
            BaselineOrigin baselineOrigin,
            Instant viewedAt) {
        Long id =
                jdbcTemplate.queryForObject(
                        """
                        INSERT INTO regime_search_log
                            (owner_user_id, snapshot_id, snapshot_key, entry_path,
                             baseline_origin, viewed_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (owner_user_id, snapshot_key, entry_path)
                        DO UPDATE SET viewed_at = EXCLUDED.viewed_at
                        RETURNING id
                        """,
                        Long.class,
                        ownerId,
                        snapshotId,
                        snapshotKey,
                        entryPath.name(),
                        baselineOrigin.name(),
                        java.sql.Timestamp.from(viewedAt));
        return id;
    }

    @Override
    public void touch(long logId, Instant viewedAt) {
        jdbcTemplate.update(
                "UPDATE regime_search_log SET viewed_at = ? WHERE id = ?",
                java.sql.Timestamp.from(viewedAt),
                logId);
    }
}
