package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.ArticleRevisionRepository;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 판은 추가·조회만 한다. 수정·삭제 메서드를 두지 않는다(data-model §3-3). */
@Repository
public class ArticleRevisionRepositoryImpl implements ArticleRevisionRepository {

    private final LearningArticleRevisionJpaRepository revisionJpaRepository;
    private final LearningArticleJpaRepository articleJpaRepository;

    public ArticleRevisionRepositoryImpl(
            LearningArticleRevisionJpaRepository revisionJpaRepository,
            LearningArticleJpaRepository articleJpaRepository) {
        this.revisionJpaRepository = revisionJpaRepository;
        this.articleJpaRepository = articleJpaRepository;
    }

    @Override
    public ArticleRevision save(ArticleRevision revision) {
        if (revision.id() != null) {
            throw new IllegalArgumentException(
                    "revisions are append-only; cannot save an existing revision: id="
                            + revision.id());
        }
        Long articleInternalId =
                PersistenceLookups.requireArticleId(
                        articleJpaRepository, revision.snapshot().articleId());
        return ArticleRevisionMapper.toDomain(
                revisionJpaRepository.save(
                        ArticleRevisionMapper.toEntity(revision, articleInternalId)));
    }

    @Override
    public Optional<ArticleRevision> findById(long revisionId) {
        return revisionJpaRepository.findById(revisionId).map(ArticleRevisionMapper::toDomain);
    }

    @Override
    public List<ArticleRevision> findByArticleId(ArticleId articleId) {
        return articleJpaRepository
                .findByArticleKey(articleId.value())
                .map(
                        article ->
                                revisionJpaRepository
                                        .findByArticleIdOrderByRevisionNoAsc(article.getId())
                                        .stream()
                                        .map(ArticleRevisionMapper::toDomain)
                                        .toList())
                .orElse(List.of());
    }
}
