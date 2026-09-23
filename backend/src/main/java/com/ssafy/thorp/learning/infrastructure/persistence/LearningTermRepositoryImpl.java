package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.LearningTermRepository;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class LearningTermRepositoryImpl implements LearningTermRepository {

    private final LearningTermJpaRepository termJpaRepository;
    private final LearningArticleJpaRepository articleJpaRepository;

    public LearningTermRepositoryImpl(
            LearningTermJpaRepository termJpaRepository,
            LearningArticleJpaRepository articleJpaRepository) {
        this.termJpaRepository = termJpaRepository;
        this.articleJpaRepository = articleJpaRepository;
    }

    @Override
    public Optional<ArticleId> findOwnerArticleKey(TermId termId) {
        return termJpaRepository
                .findByTermKey(termId.value())
                .flatMap(term -> articleJpaRepository.findById(term.getArticleId()))
                .map(article -> new ArticleId(article.getArticleKey()));
    }

    /** 벌크 삭제 뒤 다시 넣는다. 같은 {@code term_key}를 유지하는 용어도 행이 새로 생긴다. 초안 작업본이라 행 식별이 의미를 갖지 않는다. */
    @Override
    public void replaceForArticle(ArticleId articleId, List<LearningTerm> terms) {
        Long articleInternalId =
                PersistenceLookups.requireArticleId(articleJpaRepository, articleId);
        termJpaRepository.deleteByArticleIdInBulk(articleInternalId);
        termJpaRepository.saveAll(
                terms.stream()
                        .map(term -> LearningArticleMapper.termToEntity(term, articleInternalId))
                        .toList());
    }
}
