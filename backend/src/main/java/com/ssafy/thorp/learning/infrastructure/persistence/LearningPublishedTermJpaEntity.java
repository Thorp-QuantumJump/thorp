package com.ssafy.thorp.learning.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/**
 * {@code learning_published_term} 매핑. 게시 용어 색인이다.
 *
 * <p>행을 고치지 않고 글 단위로 지우고 다시 넣는다. 값은 판의 것을 그대로 옮긴다.
 *
 * <p><b>늘 새 행으로 저장한다({@link Persistable#isNew()}가 참).</b> 식별자를 직접 넣는 엔티티는 기본으로 merge가 되어, 다른 글이 가진
 * 같은 {@code term_key} 행을 오류 없이 UPDATE한다. 그러면 공개 중인 다른 글의 도움말이 말없이 넘어간다(SC-003). persist로 두면 그 충돌이
 * 제약 위반으로 드러난다.
 */
@Entity
@Table(name = "learning_published_term")
public class LearningPublishedTermJpaEntity implements Persistable<String> {

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

    @Override
    public String getId() {
        return termKey;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
