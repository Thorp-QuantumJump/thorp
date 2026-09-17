package com.ssafy.thorp.regime.domain.model;

import com.ssafy.thorp.regime.domain.vo.SearchMode;
import com.ssafy.thorp.regime.domain.vo.SearchStatus;
import com.ssafy.thorp.regime.domain.vo.SnapshotKey;
import com.ssafy.thorp.regime.domain.vo.StatusReason;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 기준월 × 모드 하나의 검색 결과 (data-model §2).
 *
 * <p><b>이 행이 곧 "검색"이며 사용자 요청은 이것을 읽을 뿐이다</b>(spec FR-030). 척도도 거리도 요청 처리 중에 만들어지지 않는다 — 여기 담긴 값은 전부
 * 사전 계산 배치가 쓴 것이고, Backend가 하는 일은 모양을 바꿔 내보내는 것뿐이다.
 *
 * <p>같은 (모드·분야·기준월)에 행이 여럿일 수 있다. 자료 개정으로 결과가 <b>실제로</b> 달라질 때마다 {@code revision}이 하나 오르고, 내용이 같으면
 * 새 행을 만들지 않는다(SC-002). 보통 조회는 가장 큰 {@code revision}을 읽고, 006 기록은 특정 {@code id}를 고정해 연다.
 */
public record RegimeSnapshot(
        long id,
        SnapshotKey key,
        SearchMode mode,
        String fieldId,
        YearMonth baselineMonth,
        int revision,
        String dataVersion,
        String registryVersion,
        String modelVersion,
        SearchStatus status,
        StatusReason statusReason,
        Map<String, IndicatorScale> scales,
        Map<String, ComparisonMonths> comparisonMonths,
        List<RankedCandidate> rankedCandidates,
        CandidateCounts candidateCounts,
        DataAsOf dataAsOf,
        Instant computedAt,
        List<RegimeCase> cases) {

    /** 임시 대역이 계산한 행인가 (data-model §6.1). */
    public boolean sampleData() {
        return startsWithFixture(dataVersion);
    }

    /** 등록표까지 임시 대역인가. 자료 버전과 따로 본다 — 한쪽만 대역인 기간이 있을 수 있다. */
    public boolean sampleRegistry() {
        return startsWithFixture(registryVersion);
    }

    /**
     * 유효 후보 수 (계약 §1의 {@code counts.eligible}).
     *
     * <p><b>빼서 구하지 않는다.</b> 전체 유효 후보 목록이 곧 그 수다 — {@code evaluated}에서 제외 사유를 빼는 셈을 여기서 하면 Backend가
     * 계산을 시작하게 되고, 그 셈이 배치의 정의와 어긋나도 아무도 알아채지 못한다(SC-002a).
     */
    public int eligibleCount() {
        return rankedCandidates.size();
    }

    /** 저장된 표시 후보 수 (계약 §1의 {@code counts.stored}). */
    public int storedCount() {
        return cases.size();
    }

    private static boolean startsWithFixture(String version) {
        // 임시 대역 판정은 이 접두사 하나다. 008에 묻지 않는다 — 008이 없어서 쓰는 대역인데
        // 그 판정을 008에 맡기면 순환이다(계약 §4.2).
        return version != null && version.startsWith("FIXTURE-");
    }
}
