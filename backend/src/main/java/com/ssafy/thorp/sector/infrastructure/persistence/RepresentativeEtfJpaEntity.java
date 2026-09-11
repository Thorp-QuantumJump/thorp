package com.ssafy.thorp.sector.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * {@code representative_etf} 테이블 매핑.
 *
 * <p>{@code leveraged}·{@code inverse}·{@code delisted_on}은 매핑하지 않는다. 앞의 둘은 저장 스키마가 TRUE인 행을 CHECK로
 * 거부하므로 읽는 쪽에 온 행은 이미 FALSE이고(FR-001), {@code delisted_on}은 이번 범위의 대응표가 현재 상장 상품만 다루기 때문이다(FR-015).
 * 읽지 않을 컬럼을 매핑하지 않는 것은 결과 조회에서 쓰는 규칙과 같다(backend/CLAUDE.md).
 */
@Entity
@Table(name = "representative_etf")
class RepresentativeEtfJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "instrument_id", nullable = false, unique = true, length = 32)
    private String instrumentId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "listed_on", nullable = false)
    private LocalDate listedOn;

    @Column(name = "benchmark_index", nullable = false, length = 200)
    private String benchmarkIndex;

    @Column(name = "manager", nullable = false, length = 200)
    private String manager;

    protected RepresentativeEtfJpaEntity() {}

    Long getId() {
        return id;
    }

    String getInstrumentId() {
        return instrumentId;
    }

    String getName() {
        return name;
    }

    LocalDate getListedOn() {
        return listedOn;
    }

    String getBenchmarkIndex() {
        return benchmarkIndex;
    }

    String getManager() {
        return manager;
    }
}
