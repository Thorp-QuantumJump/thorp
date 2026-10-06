package com.ssafy.thorp.learning.application.service;

import com.ssafy.thorp.learning.application.repository.ArticleRevisionRepository;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.LearningTermRepository;
import com.ssafy.thorp.learning.application.repository.PublicationEventRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.result.PublishResult.RenamedQuestion;
import com.ssafy.thorp.learning.application.result.PublishResult.TransferredTerm;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.ArticleSnapshot;
import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.PublicationEvent;
import com.ssafy.thorp.learning.domain.model.PublicationEvent.PublicationAction;
import com.ssafy.thorp.learning.domain.model.PublishCheckRulesV2.ReadingRules;
import com.ssafy.thorp.learning.domain.model.PublishCheckV2;
import com.ssafy.thorp.learning.domain.model.PublishMode;
import com.ssafy.thorp.learning.domain.model.QuizIdentity;
import com.ssafy.thorp.learning.domain.model.QuizQuestion;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 검사를 통과한 글 하나를 게시한다(data-model §10-1 '글마다 게시'). 글마다 게시와 단계 공개가 같은 쓰기를 쓴다. 호출자가 트랜잭션을 열고 글 행을 잠그고
 * 게시 전 검사와 용어 소유 판정({@link TermOwnership})을 먼저 통과시킨다.
 *
 * <p>쓰는 것: 새 판(같은 문제 판정 포함), 넘기기, 글 상태·공개 배치, 비어 있는 {@code first_released_at}(CURRENT 게시), 게시 용어
 * 색인, {@code PUBLISH} 기록. 변경 기록은 호출자가 쓴다. 글마다 게시는 {@code ARTICLE_ADDED} 한 줄, 단계 공개는 단계에 한 줄이다.
 *
 * <p><b>넘기기</b>(data-model §7-2, research R24): 옛 소유 글의 그 용어 게시 색인 행과 초안 행을 지우고 옛 글의 초안 번호를 1 올린 뒤,
 * 이 글의 넘겨받는 중인 행을 소유 중인 행으로 바꾸고 이 글의 판으로 색인을 쓴다. 옛 글의 판과 초안의 다른 부분은 바꾸지 않는다. 지우기가 먼저여야 부분 유일 색인과 색인
 * 기본키에 걸리지 않는다.
 */
@Component
public class ArticlePublisher {

    private final LearningArticleRepository articleRepository;
    private final LearningTermRepository termRepository;
    private final ArticleRevisionRepository revisionRepository;
    private final PublishedTermRepository publishedTermRepository;
    private final PublicationEventRepository eventRepository;

    public ArticlePublisher(
            LearningArticleRepository articleRepository,
            LearningTermRepository termRepository,
            ArticleRevisionRepository revisionRepository,
            PublishedTermRepository publishedTermRepository,
            PublicationEventRepository eventRepository) {
        this.articleRepository = articleRepository;
        this.termRepository = termRepository;
        this.revisionRepository = revisionRepository;
        this.publishedTermRepository = publishedTermRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * 게시 한 번의 결과.
     *
     * @param article 게시한 뒤의 글
     * @param firstRelease 이 게시가 공개된 단계에 처음 게시한 것이다({@code first_released_at}이 비어 있었다)
     * @param hadLegacyRevision 이 게시 전에 LEGACY 판이 있었다. {@code firstRelease}일 때만 본다
     */
    public record Outcome(
            LearningArticle article,
            int revisionNo,
            List<RenamedQuestion> renamed,
            List<TransferredTerm> transferred,
            boolean firstRelease,
            boolean hadLegacyRevision) {

        public Outcome {
            renamed = List.copyOf(renamed);
            transferred = List.copyOf(transferred);
        }
    }

    /**
     * @param article 잠근 뒤 다시 읽은 글. 검사와 용어 소유 판정을 통과했다
     * @param reading {@code CURRENT} 게시가 판에 넣을 읽기 값의 규칙
     * @param publishedAt 판·기록의 시각. 단계 공개는 모든 글에 같은 값을 넘긴다
     */
    public Outcome publish(
            LearningArticle article,
            int expectedDraftVersion,
            PublishMode mode,
            String checkVersion,
            String mapVersion,
            ReadingRules reading,
            long actorUserId,
            Instant publishedAt) {
        ArticleId id = article.articleId();
        // 다음 판 번호와 같은 문제 판정의 기준은 가장 최근 판 하나로 정한다. 옛 판 본문을 모두 읽지 않는다.
        Optional<ArticleRevision> latest = revisionRepository.findLatestByArticleId(id);
        int revisionNo = latest.map(ArticleRevision::revisionNo).orElse(0) + 1;
        boolean firstRelease = mode == PublishMode.CURRENT && article.firstReleasedAt() == null;
        boolean hadLegacyRevision =
                firstRelease && revisionRepository.existsByArticleIdAndEdition(id, Edition.LEGACY);

        List<RenamedQuestion> renamed = List.of();
        if (mode == PublishMode.CURRENT) {
            List<QuizQuestion> latestQuiz =
                    latest.map(revision -> revision.snapshot().quiz()).orElse(List.of());
            QuizIdentity.Reconciled reconciled =
                    QuizIdentity.reconcile(article.quiz(), latestQuiz, article.quizSeq());
            article = article.withReconciledQuiz(reconciled);
            renamed =
                    reconciled.renamed().stream()
                            .map(
                                    rename ->
                                            new RenamedQuestion(
                                                    rename.from().value(), rename.to().value()))
                            .toList();
        }

        List<TransferredTerm> transferred = transferTerms(article);
        article = article.withTransfersCompleted();

        ArticleRevision revision =
                revisionRepository.save(
                        new ArticleRevision(
                                null,
                                revisionNo,
                                mode.edition(),
                                snapshot(article, mode, reading),
                                checkVersion,
                                mapVersion,
                                actorUserId,
                                publishedAt));
        LearningArticle published = article.publish(expectedDraftVersion, revision.id());
        if (firstRelease) {
            published = published.firstReleased(publishedAt);
        }
        articleRepository.save(published);
        publishedTermRepository.replaceForArticle(
                id, revision.id(), published.terms(), published.format());
        eventRepository.append(
                new PublicationEvent(
                        null,
                        id,
                        PublicationAction.PUBLISH,
                        revision.id(),
                        actorUserId,
                        publishedAt));
        return new Outcome(
                published, revisionNo, renamed, transferred, firstRelease, hadLegacyRevision);
    }

    /**
     * 이 글이 넘겨받는 용어를 옛 소유 글에서 가져온다. 옛 글마다 초안 번호는 한 번만 올린다. 용어를 여럿 넘겨도 편집 화면이 다시 읽을 일은 하나다.
     *
     * @return 넘긴 용어, 이 글의 용어 순서
     */
    private List<TransferredTerm> transferTerms(LearningArticle article) {
        // 옛 소유 글을 articleId 순서로 잠근다. 두 글이 같은 옛 글 둘에서 거꾸로 된 순서로 넘겨받아 서로 기다리는 일을 막는다.
        Map<ArticleId, List<TermId>> byOwner =
                new TreeMap<>(Comparator.comparing(ArticleId::value));
        for (LearningTerm term : article.terms()) {
            if (term.transferring()) {
                byOwner.computeIfAbsent(term.transferFrom(), owner -> new ArrayList<>())
                        .add(term.termId());
            }
        }
        if (byOwner.isEmpty()) {
            return List.of();
        }
        List<TransferredTerm> transferred = new ArrayList<>();
        for (Map.Entry<ArticleId, List<TermId>> entry : byOwner.entrySet()) {
            ArticleId owner = entry.getKey();
            // 옛 글을 먼저 잠근다. 옛 글의 게시가 같은 때 색인을 다시 쓰고 있으면 그 커밋을 기다린 뒤 지운다.
            // 잠그기 전에 지우면 그 게시가 새로 넣은 색인 행을 보지 못해 남기고, 이 글의 색인 행과 겹친다.
            articleRepository
                    .findByArticleKeyForUpdate(owner)
                    .orElseThrow(
                            () -> new IllegalStateException("transfer source vanished: " + owner));
            for (TermId termId : entry.getValue()) {
                publishedTermRepository.deleteTerm(owner, termId);
                termRepository.deleteOwnedTerm(owner, termId);
            }
            // 행을 지운 뒤 다시 읽는다. 벌크 삭제가 영속성 컨텍스트를 비우므로 잠근 시점의 객체를 다시 쓰지 않는다.
            LearningArticle previous =
                    articleRepository
                            .findByArticleKeyForUpdate(owner)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "transfer source vanished: " + owner));
            articleRepository.save(previous.termsTransferredAway(entry.getValue()));
        }
        termRepository.completeTransfers(article.articleId());
        for (LearningTerm term : article.terms()) {
            if (term.transferring()) {
                transferred.add(
                        new TransferredTerm(
                                term.termId().value(),
                                term.transferFrom().value(),
                                article.articleId().value()));
            }
        }
        return transferred;
    }

    private static ArticleSnapshot snapshot(
            LearningArticle article, PublishMode mode, ReadingRules reading) {
        if (mode == PublishMode.CURRENT) {
            return new ArticleSnapshot(
                    ArticleFormat.V2,
                    article.articleId(),
                    article.title(),
                    article.question(),
                    article.readingOrder(),
                    article.blocks(),
                    article.example(),
                    article.sources(),
                    article.terms(),
                    article.draftVersion(),
                    article.quiz(),
                    PublishCheckV2.reading(article, reading));
        }
        return ArticleSnapshot.legacy(
                article.articleId(),
                article.title(),
                article.question(),
                article.readingOrder(),
                article.blocks(),
                article.example(),
                article.sources(),
                article.terms(),
                article.draftVersion());
    }
}
