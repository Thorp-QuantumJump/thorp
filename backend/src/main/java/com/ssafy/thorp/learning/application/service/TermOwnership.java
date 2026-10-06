package com.ssafy.thorp.learning.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.learning.application.exception.TermIdTakenException;
import com.ssafy.thorp.learning.application.exception.TermTransferInvalidException;
import com.ssafy.thorp.learning.application.repository.LearningTermRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.domain.model.CheckLocation;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.PublishCheckFailure;
import com.ssafy.thorp.learning.domain.model.PublishCheckFailure.Check;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 글의 초안 용어가 {@code termId}를 쓸 수 있는가(data-model §2·§7-2, research R24). 저장·글마다 게시·단계 공개가 같은 판정을 쓴다.
 * 저장과 글마다 게시는 첫 문제를 409로, 단계 공개는 모든 문제를 422의 글별 항목으로 돌려준다.
 *
 * <p>지금 소유 글은 그 용어의 소유 중인 초안 행이 있는 글, 없으면 게시 용어 색인 행이 있는 글이다. 글 A가 초안에서 뺀 용어도 A를 다시 게시하거나 게시 취소하기
 * 전까지는 색인에 남아 A가 소유한다.
 *
 * <ul>
 *   <li>넘겨받지 않는 용어: 다른 글이 소유하면 {@code TERM_ID_TAKEN}
 *   <li>넘겨받는 용어: {@code transferFrom}이 지금 소유 글이 아니거나(소유 글이 없거나 자기 글인 경우 포함), 다른 글이 이미 그 용어를 넘겨받는
 *       중이면 {@code TERM_TRANSFER}
 * </ul>
 */
@Component
public class TermOwnership {

    private final LearningTermRepository termRepository;
    private final PublishedTermRepository publishedTermRepository;

    public TermOwnership(
            LearningTermRepository termRepository,
            PublishedTermRepository publishedTermRepository) {
        this.termRepository = termRepository;
        this.publishedTermRepository = publishedTermRepository;
    }

    /**
     * 용어 하나의 소유 문제.
     *
     * @param check {@code TERM_ID_TAKEN} 또는 {@code TERM_TRANSFER}
     * @param owner 지금 소유 글. 넘겨받으려는 용어의 소유 글이 없으면 null이다
     */
    public record Violation(Check check, TermId termId, ArticleId owner) {

        /** 저장·글마다 게시의 409. */
        public BusinessException toException() {
            return check == Check.TERM_ID_TAKEN
                    ? new TermIdTakenException(termId, owner)
                    : new TermTransferInvalidException(termId, owner);
        }

        /** 단계 공개의 422 글별 항목. 위치는 그 용어다. */
        public PublishCheckFailure toFailure() {
            String message =
                    check == Check.TERM_ID_TAKEN
                            ? "'"
                                    + termId.value()
                                    + "'는 다른 글('"
                                    + owner.value()
                                    + "')이 쓰고 있는 용어입니다. 넘겨받으려면 transferFrom에 그 글을 적습니다."
                            : owner == null
                                    ? "'" + termId.value() + "'를 넘겨줄 소유 글이 없습니다."
                                    : "'"
                                            + termId.value()
                                            + "'의 지금 소유 글은 '"
                                            + owner.value()
                                            + "'입니다. transferFrom이 소유 글과 다르거나 다른 글이 이미 넘겨받는 중입니다.";
            return new PublishCheckFailure(check, CheckLocation.term(termId), null, message);
        }
    }

    /**
     * 이 글의 용어 가운데 소유가 맞지 않는 것. 용어 순서다. 아무것도 쓰지 않는다.
     *
     * @param articleId 용어를 쓰려는 글
     */
    public List<Violation> violations(ArticleId articleId, List<LearningTerm> terms) {
        List<Violation> violations = new ArrayList<>();
        for (LearningTerm term : terms) {
            violation(articleId, term).ifPresent(violations::add);
        }
        return violations;
    }

    /**
     * @throws TermIdTakenException 다른 글이 소유한 용어를 넘겨받기 없이 썼다
     * @throws TermTransferInvalidException 넘겨받기의 소유 글이 맞지 않는다
     */
    public void require(ArticleId articleId, List<LearningTerm> terms) {
        List<Violation> violations = violations(articleId, terms);
        if (!violations.isEmpty()) {
            throw violations.getFirst().toException();
        }
    }

    private Optional<Violation> violation(ArticleId articleId, LearningTerm term) {
        TermId termId = term.termId();
        Optional<ArticleId> draftOwner = termRepository.findOwnerArticleKey(termId);
        Optional<ArticleId> indexOwner = publishedTermRepository.findOwnerArticleKey(termId);
        if (!term.transferring()) {
            return draftOwner
                    .filter(owner -> !owner.equals(articleId))
                    .or(() -> indexOwner.filter(owner -> !owner.equals(articleId)))
                    .map(owner -> new Violation(Check.TERM_ID_TAKEN, termId, owner));
        }
        ArticleId from = term.transferFrom();
        Optional<ArticleId> owner = draftOwner.or(() -> indexOwner);
        boolean valid =
                owner.isPresent()
                        && owner.get().equals(from)
                        && !from.equals(articleId)
                        && indexOwner.map(from::equals).orElse(true)
                        && termRepository
                                .findIncomingArticleKey(termId)
                                .map(articleId::equals)
                                .orElse(true);
        return valid
                ? Optional.empty()
                : Optional.of(new Violation(Check.TERM_TRANSFER, termId, owner.orElse(null)));
    }
}
