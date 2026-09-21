package com.ssafy.thorp.regimelink.domain.model;

import com.ssafy.thorp.regimelink.domain.vo.EntryPath;
import com.ssafy.thorp.regimelink.domain.vo.LinkSearchId;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * 어느 검색에 어느 비교 묶음을 어느 필터로 붙였나 (data-model §3).
 *
 * <p>006이 읽는 경제환경 탐색 기록의 011 몫이며 <b>검색 경로별로 별도 행</b>이다. 목록을 확정할 때 만들고 결과가 0건이어도 저장한다 — 무엇을 찾아 무엇도
 * 나오지 않았는지가 기록이다.
 *
 * @param snapshotId 010 revision 고정. 철회되면 null 이 되고 {@code snapshotKey} 사본이 남는다
 * @param basis 역사 탐색의 근거 표기. {@code HISTORICAL_REVISION} 고정이다(FR-015)
 */
public record RegimeLinkSearch(
        Long id,
        LinkSearchId searchId,
        Long ownerUserId,
        EntryPath entryPath,
        Long snapshotId,
        String snapshotKey,
        String mode,
        String fieldId,
        YearMonth baselineMonth,
        Long baseRunId,
        Long compareRunId,
        Long baseRequestId,
        Long compareRequestId,
        Long filterId,
        List<ShownCase> shownCases,
        int excludedCount,
        boolean fullSearchShown,
        Long previousSearchId,
        Retention retention,
        String basis,
        String clientRequestId,
        Instant createdAt) {

    /** 011이 내보내는 유일한 근거 표기. 당시 재현은 범위 밖이다(FR-015). */
    public static final String BASIS_HISTORICAL_REVISION = "HISTORICAL_REVISION";

    public RegimeLinkSearch {
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(entryPath, "entryPath must not be null");
        Objects.requireNonNull(snapshotKey, "snapshotKey must not be null");
        Objects.requireNonNull(baselineMonth, "baselineMonth must not be null");
        Objects.requireNonNull(baseRunId, "baseRunId must not be null");
        Objects.requireNonNull(clientRequestId, "clientRequestId must not be null");
        shownCases = shownCases == null ? List.of() : List.copyOf(shownCases);
        basis = basis == null ? BASIS_HISTORICAL_REVISION : basis;
        if (excludedCount < 0) {
            throw new IllegalArgumentException("excludedCount 가 음수입니다: " + excludedCount);
        }
        if (entryPath.filtered() && filterId == null) {
            throw new IllegalArgumentException("진단 결과 경로에는 필터가 있어야 합니다.");
        }
    }
}
