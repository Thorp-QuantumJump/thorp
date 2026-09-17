package com.ssafy.thorp.learning.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code learning_published_term} 매핑. 게시 용어 색인이다.
 *
 * <p>행을 고치지 않고 글 단위로 지우고 다시 넣는다. 값은 판의 것을 그대로 옮긴다.
 */
@Entity
@Table(name = "learning_published_term")
public class LearningPublishedTermJpaEntity {

    @Id
    @Column(length = 64)
    private String termKey;

    @Column(nullable = false)
    private Long articleId;

    @Column(nullable = false)
    private Long revisionId;

    @Column(nullable = false, length = 50)
    private String label;

    @Column(nullable = false, length = 300)
    private String sentence;

    protected LearningPublishedTermJpaEntity() {}

    LearningPublishedTermJpaEntity(
            String termKey, Long articleId, Long revisionId, String label, String sentence) {
        this.termKey = termKey;
        this.articleId = articleId;
        this.revisionId = revisionId;
        this.label = label;
        this.sentence = sentence;
    }
}
