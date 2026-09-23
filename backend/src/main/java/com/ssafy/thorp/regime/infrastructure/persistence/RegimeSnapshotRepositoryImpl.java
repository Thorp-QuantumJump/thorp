package com.ssafy.thorp.regime.infrastructure.persistence;

import com.ssafy.thorp.regime.application.exception.RegimeLookupFailedException;
import com.ssafy.thorp.regime.application.repository.RegimeSnapshotRepository;
import com.ssafy.thorp.regime.domain.model.RegimeCase;
import com.ssafy.thorp.regime.domain.model.RegimeSnapshot;
import com.ssafy.thorp.regime.domain.vo.SearchMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사전 계산 결과 조회 구현. <b>읽기 전용이다.</b>
 *
 * <p>저장소가 읽기에 실패하면 {@link RegimeLookupFailedException}으로 알린다 — <b>빈 결과로 바꾸지 않는다</b>(spec FR-034).
 * {@link Optional#empty()}는 "그런 행이 없다"이고 예외는 "읽지 못했다"라, 둘을 같은 값으로 뭉치면 위에서 다시 가를 수 없다.
 */
@Repository
public class RegimeSnapshotRepositoryImpl implements RegimeSnapshotRepository {

    private final RegimeSnapshotJpaRepository snapshots;
    private final RegimeCaseJpaRepository cases;

    public RegimeSnapshotRepositoryImpl(
            RegimeSnapshotJpaRepository snapshots, RegimeCaseJpaRepository cases) {
        this.snapshots = snapshots;
        this.cases = cases;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegimeSnapshot> findLatest(
            SearchMode mode, String fieldId, YearMonth baselineMonth) {
        LocalDate month = baselineMonth.atDay(1);
        try {
            return snapshots.findLatest(mode.name(), fieldId, month, Limit.of(1)).stream()
                    .findFirst()
                    .map(this::withCases);
        } catch (DataAccessException e) {
            throw new RegimeLookupFailedException(
                    "저장된 검색 결과를 읽지 못했습니다: " + mode + " " + fieldId + " " + baselineMonth, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegimeSnapshot> findByKey(String snapshotKey) {
        try {
            return snapshots.findBySnapshotKey(snapshotKey).map(this::withCases);
        } catch (DataAccessException e) {
            throw new RegimeLookupFailedException("저장된 검색 결과를 읽지 못했습니다: " + snapshotKey, e);
        }
    }

    /**
     * 사례를 함께 읽는다.
     *
     * <p><b>부분 적재 항목은 여기서 걸러지지 않는다</b> — 걸러질 필요가 없다. 배치가 snapshot·사례·기준월 기록을 한 트랜잭션으로 쓰므로 머리만 남은
     * 항목은 애초에 커밋되지 않는다(spec FR-034, SC-010).
     */
    private RegimeSnapshot withCases(RegimeSnapshotJpaEntity entity) {
        List<RegimeCase> rows =
                cases.findBySnapshotIdOrderByRankAsc(entity.getId()).stream()
                        .map(RegimeCaseMapper::toDomain)
                        .toList();
        return RegimeSnapshotMapper.toDomain(entity, rows);
    }
}
