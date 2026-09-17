package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code learning_term} 매핑. 초안 용어 설명이며 통째로 바꾼다(지우고 다시 넣는다). */
@Entity
@Table(name = "learning_term")
public class LearningTermJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, updatable = false)
    private String termKey;

    @Column(nullable = false, updatable = false)
    private Long articleId;

    @Column(nullable = false, length = 50)
    private String label;

    @Column(nullable = false, length = 300)
    private String sentence;

    protected LearningTermJpaEntity() {}

    LearningTermJpaEntity(String termKey, Long articleId, String label, String sentence) {
        this.termKey = termKey;
        this.articleId = articleId;
        this.label = label;
        this.sentence = sentence;
    }

    String getTermKey() {
        return termKey;
    }

    Long getArticleId() {
        return articleId;
    }

    String getLabel() {
        return label;
    }

    String getSentence() {
        return sentence;
    }
}
