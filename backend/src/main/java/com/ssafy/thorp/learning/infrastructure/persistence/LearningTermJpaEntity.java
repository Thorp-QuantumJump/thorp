package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code learning_term} 매핑. 초안 용어 설명이며 통째로 바꾼다(지우고 다시 넣는다). V1 용어는 {@code sentence}를, V2 용어는 다른 이름과
 * 세 문장을 쓰고 쓰지 않는 열은 빈 값이다(data-model §3-3).
 */
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> aliases;

    @Column(nullable = false, length = 200)
    private String meaning;

    @Column(nullable = false, length = 200)
    private String reading;

    @Column(nullable = false, length = 200)
    private String caveat;

    protected LearningTermJpaEntity() {}

    LearningTermJpaEntity(
            String termKey,
            Long articleId,
            String label,
            String sentence,
            List<String> aliases,
            String meaning,
            String reading,
            String caveat) {
        this.termKey = termKey;
        this.articleId = articleId;
        this.label = label;
        this.sentence = sentence;
        this.aliases = aliases;
        this.meaning = meaning;
        this.reading = reading;
        this.caveat = caveat;
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

    List<String> getAliases() {
        return aliases;
    }

    String getMeaning() {
        return meaning;
    }

    String getReading() {
        return reading;
    }

    String getCaveat() {
        return caveat;
    }
}
