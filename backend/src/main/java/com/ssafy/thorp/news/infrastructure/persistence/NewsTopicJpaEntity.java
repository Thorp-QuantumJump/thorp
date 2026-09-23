package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * {@code news_topic} 매핑. 등록된 경제 주제다.
 *
 * <p>행의 원본은 `news-link-v1.json` 등록표다. 저장소는 등록표의 주제를 행으로 확보할 뿐 새 주제를 만들지 않는다.
 */
@Entity
@Table(name = "news_topic")
public class NewsTopicJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "news_topic_seq_generator")
    @SequenceGenerator(
            name = "news_topic_seq_generator",
            sequenceName = "news_topic_seq",
            allocationSize = 50)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String topicKey;

    @Column(nullable = false, length = 200)
    private String displayName;

    @Column(nullable = false)
    private boolean domesticLink;

    @Column(nullable = false, length = 64)
    private String ruleVersion;

    protected NewsTopicJpaEntity() {}

    NewsTopicJpaEntity(
            Long id,
            String topicKey,
            String displayName,
            boolean domesticLink,
            String ruleVersion) {
        this.id = id;
        this.topicKey = topicKey;
        this.displayName = displayName;
        this.domesticLink = domesticLink;
        this.ruleVersion = ruleVersion;
    }

    public Long getId() {
        return id;
    }

    public String getTopicKey() {
        return topicKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isDomesticLink() {
        return domesticLink;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }
}
