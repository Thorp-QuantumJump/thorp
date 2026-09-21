package com.ssafy.thorp.myrecord.domain.model;

import com.ssafy.thorp.myrecord.domain.vo.AnalysisPeriod;
import com.ssafy.thorp.myrecord.domain.vo.RecordId;
import com.ssafy.thorp.myrecord.domain.vo.RecordName;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 내 기록의 개인 항목(spec 006 Key Entities).
 *
 * <p><b>공용 계산 결과를 가리키는 소유 기록이다.</b> 결과·거래 내역·재현 기록을 담지 않고 {@link RecordRef}로 가리킨다. 항목을 지우면 소유 기록만
 * 사라지고 공용 결과는 남는다(FR-017, 2026-09-18 명료화).
 *
 * <p><b>복제하는 값은 저장 뒤 바뀌지 않는 조건뿐이다</b>(research R2). 섹터·전략·분석 기간은 접수 시점에 정해져 이후 바뀌지 않으므로 목록의 정렬·필터를
 * 위해 복제한다. 상태(진행·완료·실패)는 복제하지 않고 원본을 조인해 읽는다 — 갱신 누락이 조용한 거짓으로 드러나기 때문이다(원칙 VI).
 *
 * <p>불변이다. 이름 변경·삭제·열람 표시는 새 인스턴스를 돌려준다.
 *
 * @param id DB PK. 저장 전에는 {@code null}이다
 * @param recordId 사용자에게 보이는 식별자. 저장 시 부여된다
 * @param lastViewedAt 마지막 '다시 보기' 시각. 보관 기간(PD-13)을 나중에 도입할 수 있게 둔 자리이며 <b>시간 기반 자동 삭제는 만들지 않는다</b>
 * @param isCopy 같은 원본을 가리키는 별도 이름의 사본인가. 이번 범위에서 만들지 않지만 저장 구조가 막지 않는다(research R6)
 */
public record MyRecord(
        Long id,
        RecordId recordId,
        long ownerId,
        RecordRef ref,
        RecordName name,
        List<String> sectorCodes,
        List<String> strategyIds,
        AnalysisPeriod period,
        Instant savedAt,
        Instant lastViewedAt,
        Instant deletedAt,
        boolean isCopy) {

    public MyRecord {
        Objects.requireNonNull(ref, "ref must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(savedAt, "savedAt must not be null");
        sectorCodes =
                List.copyOf(Objects.requireNonNull(sectorCodes, "sectorCodes must not be null"));
        strategyIds =
                List.copyOf(Objects.requireNonNull(strategyIds, "strategyIds must not be null"));
    }

    /** 접수가 만든 새 항목. 식별자는 저장소가 붙인다(PK가 나와야 만들 수 있다). */
    public static MyRecord create(
            long ownerId,
            RecordRef ref,
            RecordName name,
            List<String> sectorCodes,
            List<String> strategyIds,
            AnalysisPeriod period,
            Instant savedAt) {
        return new MyRecord(
                null,
                null,
                ownerId,
                ref,
                name,
                sectorCodes,
                strategyIds,
                period,
                savedAt,
                null,
                null,
                false);
    }

    /** 종류는 참조에서 나온다 — 둘이 어긋날 수 없다. */
    public RecordKind kind() {
        return ref.kind();
    }

    /** 이름만 바뀐다. 저장된 결과와 참조 버전은 그대로다(FR-017). */
    public MyRecord rename(RecordName newName) {
        return new MyRecord(
                id,
                recordId,
                ownerId,
                ref,
                Objects.requireNonNull(newName),
                sectorCodes,
                strategyIds,
                period,
                savedAt,
                lastViewedAt,
                deletedAt,
                isCopy);
    }

    /** '다시 보기'로 열었다. 보관 기간을 나중에 도입할 수 있게 시각만 남긴다. */
    public MyRecord markViewed(Instant at) {
        return new MyRecord(
                id,
                recordId,
                ownerId,
                ref,
                name,
                sectorCodes,
                strategyIds,
                period,
                savedAt,
                Objects.requireNonNull(at, "lastViewedAt must not be null"),
                deletedAt,
                isCopy);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 남의 기록인가. 아니라고 답하는 쪽이 404를 낸다 — 403은 그 기록이 있다는 사실을 알려 준다. */
    public boolean ownedBy(long userId) {
        return ownerId == userId;
    }
}
