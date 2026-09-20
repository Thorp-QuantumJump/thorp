package com.ssafy.thorp.myrecord.application.port;

import com.ssafy.thorp.myrecord.domain.vo.EntryPath;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import java.time.Instant;
import java.util.Optional;

/**
 * 경제환경 개인 검색 기록({@code regime_search_log})에 행을 쓰는 통로(spec 006 research R15).
 *
 * <p><b>표는 010이 만들고 행은 006이 쓴다.</b> 010은 이 표를 읽기만 한다고 정해 두었다(FR-032) — 010의 읽기 저장소는 손대지 않고, 006이 자기
 * 포트를 둔다.
 *
 * <p>진입 경로는 스냅샷의 성질이 아니라 <b>요청의 성질</b>이고 그것을 아는 것은 조회한 화면뿐이다. 그래서 서버가 조회(GET) 중에 자동으로 남기지 않고 006의
 * 기록 요청이 값을 받아 남긴다 — 조회에 쓰기 부작용을 붙이지 않는다.
 */
public interface RegimeSearchLogWriter {

    /**
     * 같은 소유자·같은 스냅샷 키·같은 진입 경로의 기존 행.
     *
     * <p>있으면 새 행을 만들지 않고 {@link #touch}로 열람 시각만 올린다 — 같은 검색을 두 번 열었다고 기록이 둘이 되면 목록이 같은 항목으로 찬다.
     */
    Optional<Long> findActive(long ownerId, String snapshotKey, EntryPath entryPath);

    /**
     * 행을 하나 만든다.
     *
     * @param snapshotId 고정할 revision. 철회로 사라지면 NULL이 되고 {@code snapshotKey}로 무엇을 봤는지는 남는다
     * @return 만들어진 행의 id. 기록 항목이 이 값을 가리킨다
     */
    long append(
            long ownerId,
            String snapshotKey,
            Long snapshotId,
            EntryPath entryPath,
            BaselineOrigin baselineOrigin,
            Instant viewedAt);

    /** 이미 있는 행의 열람 시각만 올린다. */
    void touch(long logId, Instant viewedAt);
}
