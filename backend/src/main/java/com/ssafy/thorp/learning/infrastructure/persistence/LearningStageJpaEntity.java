package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code learning_stage} 매핑. 정의 열(식별자·번호·이름·수준 표시·한 줄 설명)은 마이그레이션만 쓰므로 {@code updatable = false}다.
 * 공개 열은 '단계 공개'가 잠근 행에 한 번 쓴다({@link #release}).
 */
@Entity
@Table(name = "learning_stage")
public class LearningStageJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, updatable = false)
    private String stageKey;

    @Column(nullable = false, updatable = false)
    private short number;

    @Column(nullable = false, length = 40, updatable = false)
    private String name;

    @Column(nullable = false, length = 16, updatable = false)
    private String levelLabel;

    @Column(nullable = false, length = 120, updatable = false)
    private String summary;

    private Instant releasedAt;

    private Long releasedByUserId;

    protected LearningStageJpaEntity() {}

    /** 공개 열을 한 번 쓴다. 공개 여부는 도메인({@code LearningStage.release})이 먼저 확인한다. */
    void release(Instant at, Long userId) {
        this.releasedAt = at;
        this.releasedByUserId = userId;
    }

    Long getId() {
        return id;
    }

    String getStageKey() {
        return stageKey;
    }

    int getNumber() {
        return number;
    }

    String getName() {
        return name;
    }

    String getLevelLabel() {
        return levelLabel;
    }

    String getSummary() {
        return summary;
    }

    Instant getReleasedAt() {
        return releasedAt;
    }

    Long getReleasedByUserId() {
        return releasedByUserId;
    }
}
