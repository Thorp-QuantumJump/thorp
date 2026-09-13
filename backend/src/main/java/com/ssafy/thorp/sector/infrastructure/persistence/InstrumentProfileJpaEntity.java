package com.ssafy.thorp.sector.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code instrument_profile} 테이블 매핑 (FR-015).
 *
 * <p>자료 버전마다 대표 ETF 한 건이다. 시계열과 달리 문자열 {@code dataset_id}가 아니라 두 bigint 외래 키를 갖는다(data-model.md) —
 * 가격처럼 날짜별로 쌓이는 자료가 아니라 상품 하나에 딸린 표시용 묶음이기 때문이다.
 *
 * <p>{@code avg_daily_value_3m}이 {@code NUMERIC}이 아니라 {@code TEXT}인 이유: {@code NUMERIC} 컬럼은 scale을
 * 임의로 정해야 하고 넘는 값을 조용히 반올림한다({@code backend/CLAUDE.md}). 파이프라인이 지수 표기 없는 문자열로 넣고 저장 CHECK이 그 모양을
 * 강제하므로, {@link InstrumentProfileMapper}가 그대로 {@code BigDecimal}로 옮길 수 있다.
 *
 * <p>{@code holdings_overlap}은 {@code jsonb}다. {@code @JdbcTypeCode(SqlTypes.JSON)}의 직렬화는 {@code
 * MessageJsonPersistenceConfig}가 메시지 ObjectMapper에 맡겨 두었고, 그 매퍼는 decimal을 plain 문자열로 다룬다. 그래서 비중이
 * {@code String}으로 읽히며 저장된 자리수가 그대로 보존된다.
 *
 * <p><b>추적 차이 컬럼이 없다.</b> 계산 정의가 확정되지 않아 이번 범위에서 뺐다(PD-21).
 */
@Entity
@Table(name = "instrument_profile")
class InstrumentProfileJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "etf_id", nullable = false)
    private RepresentativeEtfJpaEntity etf;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_version_id", nullable = false)
    private DatasetVersionJpaEntity datasetVersion;

    @Column(name = "index_methodology_summary", nullable = false, columnDefinition = "text")
    private String indexMethodologySummary;

    /** {@code {"topHoldings": [{"name": ..., "weight": "0.2431"}]}} 모양이다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "holdings_overlap", nullable = false)
    private Map<String, Object> holdingsOverlap;

    /** true면 화면은 "당시 구성을 재현했다"고 표현하지 않는다(FR-015). */
    @Column(name = "holdings_as_of_current", nullable = false)
    private boolean holdingsAsOfCurrent;

    @Column(name = "avg_daily_value_3m", nullable = false, columnDefinition = "text")
    private String avgDailyValue3m;

    @Column(name = "source_attribution", nullable = false, length = 200)
    private String sourceAttribution;

    @Column(name = "survivorship_note", nullable = false, columnDefinition = "text")
    private String survivorshipNote;

    protected InstrumentProfileJpaEntity() {}

    Long getId() {
        return id;
    }

    RepresentativeEtfJpaEntity getEtf() {
        return etf;
    }

    DatasetVersionJpaEntity getDatasetVersion() {
        return datasetVersion;
    }

    String getIndexMethodologySummary() {
        return indexMethodologySummary;
    }

    Map<String, Object> getHoldingsOverlap() {
        return holdingsOverlap;
    }

    boolean isHoldingsAsOfCurrent() {
        return holdingsAsOfCurrent;
    }

    String getAvgDailyValue3m() {
        return avgDailyValue3m;
    }

    String getSourceAttribution() {
        return sourceAttribution;
    }

    String getSurvivorshipNote() {
        return survivorshipNote;
    }
}
