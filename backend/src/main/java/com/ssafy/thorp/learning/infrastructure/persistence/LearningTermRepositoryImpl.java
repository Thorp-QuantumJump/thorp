package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.LearningTermRepository;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
                .findByTermKeyAndTransferFromArticleIdIsNull(termId.value())
                .flatMap(term -> articleJpaRepository.findArticleKeyById(term.getArticleId()))
                .map(ArticleId::new);
    }

    @Override
    public Optional<ArticleId> findIncomingArticleKey(TermId termId) {
        return termJpaRepository
                .findByTermKeyAndTransferFromArticleIdIsNotNull(termId.value())
                .flatMap(term -> articleJpaRepository.findArticleKeyById(term.getArticleId()))
                .map(ArticleId::new);
    }

    @Override
    public Set<TermId> findAllTermIds() {
        Set<TermId> termIds = new LinkedHashSet<>();
        termJpaRepository.findAllTermKeys().forEach(key -> termIds.add(new TermId(key)));
        return termIds;
    }

    /** 벌크 삭제 뒤 다시 넣는다. 같은 {@code term_key}를 유지하는 용어도 행이 새로 생긴다. 초안 작업본이라 행 식별이 의미를 갖지 않는다. */
    @Override
    public void replaceForArticle(ArticleId articleId, List<LearningTerm> terms) {
        Long articleInternalId =
                PersistenceLookups.requireArticleId(articleJpaRepository, articleId);
        termJpaRepository.deleteByArticleIdInBulk(articleInternalId);
        termJpaRepository.saveAll(
                terms.stream()
                        .map(
                                term ->
                                        LearningArticleMapper.termToEntity(
                                                term,
                                                articleInternalId,
                                                term.transferFrom() == null
                                                        ? null
                                                        : PersistenceLookups.requireArticleId(
                                                                articleJpaRepository,
                                                                term.transferFrom())))
                        .toList());
    }

    @Override
    public void deleteOwnedTerm(ArticleId ownerArticleId, TermId termId) {
        termJpaRepository.deleteOwnedInBulk(
                PersistenceLookups.requireArticleId(articleJpaRepository, ownerArticleId),
                termId.value());
    }

    /** 엔티티를 고쳐 저장한다. 벌크 갱신은 수정 시각(auditing)을 건너뛴다. */
    @Override
    public void completeTransfers(ArticleId articleId) {
        List<LearningTermJpaEntity> incoming =
                termJpaRepository.findByArticleIdAndTransferFromArticleIdIsNotNull(
                        PersistenceLookups.requireArticleId(articleJpaRepository, articleId));
        incoming.forEach(LearningTermJpaEntity::completeTransfer);
        termJpaRepository.saveAllAndFlush(incoming);
    }
}
