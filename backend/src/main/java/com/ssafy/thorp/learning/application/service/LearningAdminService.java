package com.ssafy.thorp.learning.application.service;

import com.ssafy.thorp.learning.application.command.CreateArticleCommand;
import com.ssafy.thorp.learning.application.command.PublishCommand;
import com.ssafy.thorp.learning.application.command.SaveDraftCommand;
import com.ssafy.thorp.learning.application.command.UnpublishCommand;
import com.ssafy.thorp.learning.application.exception.ArticleIdTakenException;
import com.ssafy.thorp.learning.application.exception.ArticleNotFoundException;
import com.ssafy.thorp.learning.application.exception.TermIdTakenException;
import com.ssafy.thorp.learning.application.port.PublishCheckRulesPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort.SectorMapView;
import com.ssafy.thorp.learning.application.repository.ArticleRevisionRepository;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.LearningTermRepository;
import com.ssafy.thorp.learning.application.repository.PublicationEventRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.result.AdminArticleResult;
import com.ssafy.thorp.learning.application.result.AdminArticleSummaryResult;
import com.ssafy.thorp.learning.application.result.AdminArticleSummaryResult.LastPublication;
import com.ssafy.thorp.learning.application.result.PublishResult;
import com.ssafy.thorp.learning.application.result.UnpublishResult;
import com.ssafy.thorp.learning.domain.exception.DraftChangedException;
import com.ssafy.thorp.learning.domain.exception.PublishCheckFailedException;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.ArticleSnapshot;
import com.ssafy.thorp.learning.domain.model.ArticleStatus;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.PublicationEvent;
import com.ssafy.thorp.learning.domain.model.PublicationEvent.PublicationAction;
import com.ssafy.thorp.learning.domain.model.PublishCheck;
import com.ssafy.thorp.learning.domain.model.PublishCheckResult;
import com.ssafy.thorp.learning.domain.model.PublishCheckRules;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배우기 관리 유스케이스(US5 서버, learning-admin-api). 권한 판정은 호출 전에 끝나 있다({@code LearningAdminAccess}).
 *
 * <p><b>초안은 어떤 경로로도 공개되지 않는다.</b> 저장은 초안 컬럼과 초안 용어만 바꾸고, 공개 내용(판·게시 용어 색인)은 게시·게시 취소만 바꾼다. 게시는 서버의
 * 게시 전 검사를 통과해야 하고, 걸리면 아무것도 남기지 않는다. 화면을 우회한 요청도 같은 검사를 받는다(FR-017).
 *
 * <p>저장·게시·게시 취소는 글 행을 잠그고 잠근 시점의 값을 다시 읽는다. 같은 글을 두 탭에서 고치면 뒤엣것이 {@code draftVersion} 불일치로
 * 거절된다(research R12).
 *
 * <p>삭제 유스케이스는 없다(spec Out of Scope).
 */
@Service
@Transactional
public class LearningAdminService {

    private final LearningArticleRepository articleRepository;
    private final LearningTermRepository termRepository;
    private final ArticleRevisionRepository revisionRepository;
    private final PublishedTermRepository publishedTermRepository;
    private final PublicationEventRepository eventRepository;
    private final SectorMapPort sectorMapPort;
    private final PublishCheckRulesPort rulesPort;
    private final Clock clock;

    public LearningAdminService(
            LearningArticleRepository articleRepository,
            LearningTermRepository termRepository,
            ArticleRevisionRepository revisionRepository,
            PublishedTermRepository publishedTermRepository,
            PublicationEventRepository eventRepository,
            SectorMapPort sectorMapPort,
            PublishCheckRulesPort rulesPort,
            Clock clock) {
        this.articleRepository = articleRepository;
        this.termRepository = termRepository;
        this.revisionRepository = revisionRepository;
        this.publishedTermRepository = publishedTermRepository;
        this.eventRepository = eventRepository;
        this.sectorMapPort = sectorMapPort;
        this.rulesPort = rulesPort;
        this.clock = clock;
    }

    /**
     * 모든 글. {@code readingOrder}, {@code articleId} 순이다. 대응표는 이 조회에서 한 번만 읽는다.
     *
     * <p>{@code needsUpdate}는 공개 중인 판의 예시로 판정한다. 초안의 예시가 아니다. 일반 사용자가 지금 보는 글이 002로 넘어갈 수 있는지가
     * 운영자에게 필요한 정보다.
     */
    @Transactional(readOnly = true)
    public List<AdminArticleSummaryResult> list() {
        Optional<Set<String>> openSectorCodes = openSectorCodes(sectorMapPort.currentMap());
        return articleRepository.findAllOrdered().stream()
                .map(article -> summarize(article, openSectorCodes))
                .toList();
    }

    /**
     * @throws ArticleIdTakenException 이미 쓰인 {@code articleId}
     */
    public AdminArticleResult create(CreateArticleCommand command) {
        if (articleRepository.existsByArticleKey(command.articleId())) {
            throw new ArticleIdTakenException();
        }
        LearningArticle created =
                articleRepository.save(
                        new LearningArticle(
                                null,
                                command.articleId(),
                                command.title(),
                                "",
                                command.readingOrder(),
                                List.of(),
                                null,
                                List.of(),
                                ArticleStatus.DRAFT,
                                null,
                                0,
                                List.of()));
        return new AdminArticleResult(created, null);
    }

    /**
     * @throws ArticleNotFoundException 없는 글, 형식이 틀린 식별자
     */
    @Transactional(readOnly = true)
    public AdminArticleResult getDraft(String articleId) {
        ArticleId id = parseArticleId(articleId);
        LearningArticle article =
                articleRepository.findByArticleKey(id).orElseThrow(ArticleNotFoundException::new);
        return new AdminArticleResult(article, publishedRevisionNo(id));
    }

    /**
     * 초안을 통째로 바꾼다. 공개 내용은 바뀌지 않는다(data-model §7).
     *
     * @throws ArticleNotFoundException 없는 글
     * @throws TermIdTakenException 요청의 {@code termId}를 다른 글이 쓰고 있다
     * @throws DraftChangedException 읽은 뒤 다른 곳에서 초안이 저장됐다
     */
    public AdminArticleResult saveDraft(String articleId, SaveDraftCommand command) {
        ArticleId id = parseArticleId(articleId);
        LearningArticle article =
                articleRepository
                        .findByArticleKeyForUpdate(id)
                        .orElseThrow(ArticleNotFoundException::new);
        for (LearningTerm term : command.terms()) {
            termRepository
                    .findOwnerArticleKey(term.termId())
                    .filter(owner -> !owner.equals(id))
                    .ifPresent(
                            owner -> {
                                throw new TermIdTakenException(term.termId(), owner);
                            });
        }
        LearningArticle saved =
                article.saveDraft(
                        command.expectedDraftVersion(),
                        command.title(),
                        command.question(),
                        command.readingOrder(),
                        command.blocks(),
                        command.example(),
                        command.sources(),
                        command.terms());
        articleRepository.save(saved);
        termRepository.replaceForArticle(id, command.terms());
        LearningArticle reloaded =
                articleRepository
                        .findByArticleKey(id)
                        .orElseThrow(
                                () -> new IllegalStateException("saved article vanished: " + id));
        return new AdminArticleResult(reloaded, publishedRevisionNo(id));
    }

    /**
     * 게시 전 검사를 통과하면 한 트랜잭션에서 새 판·글 상태·게시 용어 색인·게시 기록을 남긴다. 걸리면 아무것도 바꾸지 않는다.
     *
     * @throws ArticleNotFoundException 없는 글
     * @throws DraftChangedException 운영자가 보지 않은 초안을 게시하지 않는다
     * @throws PublishCheckFailedException 검사에 걸린 항목이 있다. 모두 싣는다
     */
    public PublishResult publish(String articleId, PublishCommand command) {
        ArticleId id = parseArticleId(articleId);
        LearningArticle article =
                articleRepository
                        .findByArticleKeyForUpdate(id)
                        .orElseThrow(ArticleNotFoundException::new);
        if (!article.isDraftVersion(command.expectedDraftVersion())) {
            throw new DraftChangedException(article.draftVersion());
        }
        Optional<SectorMapView> map = sectorMapPort.currentMap();
        PublishCheckRules rules = rulesPort.current();
        PublishCheckResult checked = PublishCheck.run(article, rules, openSectorCodes(map));
        if (!checked.passed()) {
            throw new PublishCheckFailedException(rules.version(), checked.failures());
        }
        // 검사를 통과했으면 예시와 대응표가 모두 있다(EXAMPLE_PRESENT·EXAMPLE_SECTOR_IN_MAP).
        String mapVersion =
                map.map(SectorMapView::mapVersion)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "publish check passed without a sector map"));

        Instant now = now();
        int revisionNo =
                revisionRepository.findByArticleId(id).stream()
                                .mapToInt(ArticleRevision::revisionNo)
                                .max()
                                .orElse(0)
                        + 1;
        ArticleRevision revision =
                revisionRepository.save(
                        new ArticleRevision(
                                null,
                                revisionNo,
                                new ArticleSnapshot(
                                        id,
                                        article.title(),
                                        article.question(),
                                        article.readingOrder(),
                                        article.blocks(),
                                        article.example(),
                                        article.sources(),
                                        article.terms(),
                                        article.draftVersion()),
                                rules.version(),
                                mapVersion,
                                command.actorUserId(),
                                now));
        articleRepository.save(article.publish(command.expectedDraftVersion(), revision.id()));
        publishedTermRepository.replaceForArticle(id, revision.id(), article.terms());
        eventRepository.append(
                new PublicationEvent(
                        null,
                        id,
                        PublicationAction.PUBLISH,
                        revision.id(),
                        command.actorUserId(),
                        now));
        return new PublishResult(
                id.value(), ArticleStatus.PUBLISHED, revisionNo, rules.version(), mapVersion, now);
    }

    /**
     * 글과 그 글의 용어 설명을 함께 공개에서 내린다. 초안 작업본은 그대로 남는다.
     *
     * @return 이미 초안이면 아무것도 바꾸지 않고 기록도 남기지 않은 채 {@code unpublishedRevisionNo = null}이다
     * @throws ArticleNotFoundException 없는 글
     */
    public UnpublishResult unpublish(String articleId, UnpublishCommand command) {
        ArticleId id = parseArticleId(articleId);
        LearningArticle article =
                articleRepository
                        .findByArticleKeyForUpdate(id)
                        .orElseThrow(ArticleNotFoundException::new);
        if (article.status() == ArticleStatus.DRAFT) {
            return new UnpublishResult(id.value(), ArticleStatus.DRAFT, null);
        }
        long revisionId = article.publishedRevisionId();
        int revisionNo =
                revisionRepository
                        .findById(revisionId)
                        .map(ArticleRevision::revisionNo)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "published revision not found: " + revisionId));
        articleRepository.save(article.unpublish());
        publishedTermRepository.deleteForArticle(id);
        eventRepository.append(
                new PublicationEvent(
                        null,
                        id,
                        PublicationAction.UNPUBLISH,
                        revisionId,
                        command.actorUserId(),
                        now()));
        return new UnpublishResult(id.value(), ArticleStatus.DRAFT, revisionNo);
    }

    private AdminArticleSummaryResult summarize(
            LearningArticle article, Optional<Set<String>> openSectorCodes) {
        Optional<ArticleRevision> published =
                article.status() == ArticleStatus.PUBLISHED
                        ? articleRepository.findPublishedRevision(article.articleId())
                        : Optional.empty();
        boolean hasUnpublishedChanges =
                published
                        .map(
                                revision ->
                                        revision.snapshot().draftVersion()
                                                != article.draftVersion())
                        .orElse(false);
        Boolean needsUpdate =
                published.isEmpty()
                        ? Boolean.FALSE
                        : needsUpdate(published.get().snapshot().example(), openSectorCodes);
        LastPublication lastPublication =
                eventRepository
                        .findLastByArticleId(article.articleId())
                        .map(
                                event ->
                                        new LastPublication(
                                                event.action(),
                                                event.revisionId() == null
                                                        ? null
                                                        : revisionRepository
                                                                .findById(event.revisionId())
                                                                .map(ArticleRevision::revisionNo)
                                                                .orElse(null),
                                                event.actorUserId(),
                                                event.occurredAt()))
                        .orElse(null);
        return new AdminArticleSummaryResult(
                article.articleId().value(),
                article.title(),
                article.readingOrder(),
                article.status(),
                article.draftVersion(),
                hasUnpublishedChanges,
                needsUpdate,
                lastPublication);
    }

    /**
     * 공개 중인 판의 예시가 지금 열린 대응표로 002에 넘어갈 수 없는가. 판에 예시가 없으면(게시 전 검사가 막는 상태) 갱신이 필요한 글로 본다. 목록 전체를
     * 실패시키면 운영자가 그 글을 고치러 들어갈 수도 없다.
     *
     * @return 대응표를 읽지 못했으면 null. 판정할 수 없는 것을 false로 세지 않는다
     */
    private static Boolean needsUpdate(
            ExampleAllocation example, Optional<Set<String>> openSectorCodes) {
        if (openSectorCodes.isEmpty()) {
            return null;
        }
        if (example == null) {
            return Boolean.TRUE;
        }
        return example.sectors().stream()
                .anyMatch(sector -> !openSectorCodes.get().contains(sector.code()));
    }

    private Integer publishedRevisionNo(ArticleId id) {
        return articleRepository
                .findPublishedRevision(id)
                .map(ArticleRevision::revisionNo)
                .orElse(null);
    }

    private static Optional<Set<String>> openSectorCodes(Optional<SectorMapView> map) {
        return map.map(view -> view.sectorNamesByCode().keySet());
    }

    /** DB가 마이크로초까지 저장하므로 응답 값과 저장 값이 같도록 자른다. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ArticleId parseArticleId(String raw) {
        try {
            return new ArticleId(raw);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ArticleNotFoundException();
        }
    }
}
