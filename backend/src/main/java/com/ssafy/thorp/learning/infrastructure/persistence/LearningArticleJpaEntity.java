package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.ArticleStatus;
import com.ssafy.thorp.learning.infrastructure.persistence.LearningContentJson.BlockJson;
import com.ssafy.thorp.learning.infrastructure.persistence.LearningContentJson.ExampleJson;
import com.ssafy.thorp.learning.infrastructure.persistence.LearningContentJson.QuizJson;
import com.ssafy.thorp.learning.infrastructure.persistence.LearningContentJson.SourceJson;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code learning_article} 매핑. 글의 초안 작업본이다.
 *
 * <p>용어({@code learning_term})·판·단계를 연관관계로 묶지 않고 대리키 컬럼만 둔다. 용어는 통째로 바꾸고 판은 추가만 하므로 JPA 연관의 전파가 줄
 * 이득이 없고, 오히려 판을 컬렉션에서 빼는 코드가 삭제로 번질 수 있다.
 */
@Entity
@Table(name = "learning_article")
public class LearningArticleJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, updatable = false)
    private String articleKey;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 200)
    private String question;

    @Column(nullable = false)
    private int readingOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<BlockJson> bodyBlocks;

    @JdbcTypeCode(SqlTypes.JSON)
    private ExampleJson example;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<SourceJson> sources;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ArticleStatus status;

    private Long publishedRevisionId;

    @Column(nullable = false)
    private int draftVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private ArticleFormat format;

    @Column(nullable = false)
    private Long stageId;

    @Column(nullable = false)
    private short stagePosition;

    private Long publishedStageId;

    private Short publishedStagePosition;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<QuizJson> quiz;

    @Column(nullable = false)
    private int quizSeq;

    private Instant firstReleasedAt;

    protected LearningArticleJpaEntity() {}

    LearningArticleJpaEntity(String articleKey) {
        this.articleKey = articleKey;
    }

    void apply(
            String title,
            String question,
            int readingOrder,
            List<BlockJson> bodyBlocks,
            ExampleJson example,
            List<SourceJson> sources,
            ArticleStatus status,
            Long publishedRevisionId,
            int draftVersion,
            ArticleFormat format,
            Long stageId,
            int stagePosition,
            Long publishedStageId,
            Integer publishedStagePosition,
            List<QuizJson> quiz,
            int quizSeq,
            Instant firstReleasedAt) {
        this.title = title;
        this.question = question;
        this.readingOrder = readingOrder;
        this.bodyBlocks = bodyBlocks;
        this.example = example;
        this.sources = sources;
        this.status = status;
        this.publishedRevisionId = publishedRevisionId;
        this.draftVersion = draftVersion;
        this.format = format;
        this.stageId = stageId;
        this.stagePosition = (short) stagePosition;
        this.publishedStageId = publishedStageId;
        this.publishedStagePosition =
                publishedStagePosition == null ? null : publishedStagePosition.shortValue();
        this.quiz = quiz;
        this.quizSeq = quizSeq;
        this.firstReleasedAt = firstReleasedAt;
    }

    Long getId() {
        return id;
    }

    String getArticleKey() {
        return articleKey;
    }

    String getTitle() {
        return title;
    }

    String getQuestion() {
        return question;
    }

    int getReadingOrder() {
        return readingOrder;
    }

    List<BlockJson> getBodyBlocks() {
        return bodyBlocks;
    }

    ExampleJson getExample() {
        return example;
    }

    List<SourceJson> getSources() {
        return sources;
    }

    ArticleStatus getStatus() {
        return status;
    }

    Long getPublishedRevisionId() {
        return publishedRevisionId;
    }

    int getDraftVersion() {
        return draftVersion;
    }

    ArticleFormat getFormat() {
        return format;
    }

    Long getStageId() {
        return stageId;
    }

    int getStagePosition() {
        return stagePosition;
    }

    Long getPublishedStageId() {
        return publishedStageId;
    }

    Integer getPublishedStagePosition() {
        return publishedStagePosition == null ? null : publishedStagePosition.intValue();
    }

    List<QuizJson> getQuiz() {
        return quiz;
    }

    int getQuizSeq() {
        return quizSeq;
    }

    Instant getFirstReleasedAt() {
        return firstReleasedAt;
    }
}
