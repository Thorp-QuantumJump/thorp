package com.ssafy.thorp.learning.application.service;

import com.ssafy.thorp.learning.application.command.CreateArticleCommand;
import com.ssafy.thorp.learning.application.command.PublishCommand;
import com.ssafy.thorp.learning.application.command.SaveDraftCommand;
import com.ssafy.thorp.learning.application.command.UnpublishCommand;
import com.ssafy.thorp.learning.application.exception.ArticleIdTakenException;
import com.ssafy.thorp.learning.application.exception.ArticleNotFoundException;
import com.ssafy.thorp.learning.application.exception.StageNotFoundException;
import com.ssafy.thorp.learning.application.exception.TermIdTakenException;
import com.ssafy.thorp.learning.application.exception.TermTransferInvalidException;
import com.ssafy.thorp.learning.application.port.InstitutionNoteReviewPeriod;
import com.ssafy.thorp.learning.application.port.PublishCheckRulesPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort.SectorMapView;
import com.ssafy.thorp.learning.application.repository.ArticleRevisionRepository;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.LearningStageRepository;
import com.ssafy.thorp.learning.application.repository.LearningTermRepository;
import com.ssafy.thorp.learning.application.repository.PublicationEventRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.repository.ReleaseLogRepository;
import com.ssafy.thorp.learning.application.result.AdminArticleResult;
import com.ssafy.thorp.learning.application.result.AdminArticleSummaryResult;
import com.ssafy.thorp.learning.application.result.AdminArticleSummaryResult.LastPublication;
import com.ssafy.thorp.learning.application.result.AdminArticleSummaryResult.StageSummary;
import com.ssafy.thorp.learning.application.result.LearningArticleResult;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.Source;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.StageRef;
import com.ssafy.thorp.learning.application.result.PublishResult;
import com.ssafy.thorp.learning.application.result.UnpublishResult;
import com.ssafy.thorp.learning.application.service.ArticleViewAssembler.Mode;
import com.ssafy.thorp.learning.domain.exception.DraftChangedException;
import com.ssafy.thorp.learning.domain.exception.FormatOutdatedException;
import com.ssafy.thorp.learning.domain.exception.InvalidDraftException;
import com.ssafy.thorp.learning.domain.exception.InvalidDraftException.FieldViolation;
import com.ssafy.thorp.learning.domain.exception.PublishCheckFailedException;
import com.ssafy.thorp.learning.domain.exception.StageNotReleasedException;
import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.InstitutionNoteBlock;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.ArticleStatus;
import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.LearningArticle.DraftContent;
import com.ssafy.thorp.learning.domain.model.LearningStage;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.Placement;
import com.ssafy.thorp.learning.domain.model.PublicationEvent;
import com.ssafy.thorp.learning.domain.model.PublicationEvent.PublicationAction;
import com.ssafy.thorp.learning.domain.model.PublishCheck;
import com.ssafy.thorp.learning.domain.model.PublishCheckResult;
import com.ssafy.thorp.learning.domain.model.PublishCheckRules;
import com.ssafy.thorp.learning.domain.model.PublishCheckRulesV2;
import com.ssafy.thorp.learning.domain.model.PublishCheckV2;
import com.ssafy.thorp.learning.domain.model.PublishMode;
import com.ssafy.thorp.learning.domain.model.QuizQuestion;
import com.ssafy.thorp.learning.domain.model.ReadingEstimate;
import com.ssafy.thorp.learning.domain.model.ReleaseLogEntry;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배우기 관리 유스케이스(US5 서버, learning-admin-api). 권한 판정은 호출 전에 끝나 있다({@code LearningAdminAccess}).
 *
 * <p><b>초안은 어떤 경로로도 공개되지 않는다.</b> 저장은 초안 컬럼과 초안 용어만 바꾸고, 공개 내용(판·공개 배치·게시 용어 색인)은 게시·게시 취소만 바꾼다.
 * 게시는 서버의 게시 전 검사를 통과해야 하고, 걸리면 아무것도 남기지 않는다. 화면을 우회한 요청도 같은 검사를 받는다(FR-017). 미리 보기는 초안을 읽기만 한다.
 *
 * <p><b>게시 방식은 서버가 정한다</b>({@link PublishMode}, research R16). V1 초안은 공개 전 단계에서 v1 검사로 이전 판({@code
 * LEGACY})을 고치고, V2 초안은 공개된 단계에서 v2 검사로 새 기준 판({@code CURRENT})을 만든다.
 *
 * <p>저장·게시·게시 취소는 글 행을 잠그고 잠근 시점의 값을 다시 읽는다. 같은 글을 두 탭에서 고치면 뒤엣것이 {@code draftVersion} 불일치로
 * 거절된다(research R12).
 *
 * <p>삭제 유스케이스는 없다(spec Out of Scope).
 */
@Service
@Transactional
public class LearningAdminService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final LearningArticleRepository articleRepository;
    private final LearningTermRepository termRepository;
    private final ArticleRevisionRepository revisionRepository;
    private final PublishedTermRepository publishedTermRepository;
    private final PublicationEventRepository eventRepository;
    private final LearningStageRepository stageRepository;
    private final SectorMapPort sectorMapPort;
    private final PublishCheckRulesPort rulesPort;
    private final InstitutionNoteReviewPeriod reviewPeriod;
    private final ReleaseLogRepository releaseLogRepository;
    private final TermOwnership termOwnership;
    private final ArticlePublisher publisher;
    private final Clock clock;

    public LearningAdminService(
            LearningArticleRepository articleRepository,
            LearningTermRepository termRepository,
            ArticleRevisionRepository revisionRepository,
            PublishedTermRepository publishedTermRepository,
            PublicationEventRepository eventRepository,
            LearningStageRepository stageRepository,
            SectorMapPort sectorMapPort,
            PublishCheckRulesPort rulesPort,
            InstitutionNoteReviewPeriod reviewPeriod,
            ReleaseLogRepository releaseLogRepository,
            TermOwnership termOwnership,
            ArticlePublisher publisher,
            Clock clock) {
        this.articleRepository = articleRepository;
        this.termRepository = termRepository;
        this.revisionRepository = revisionRepository;
        this.publishedTermRepository = publishedTermRepository;
        this.eventRepository = eventRepository;
        this.stageRepository = stageRepository;
        this.sectorMapPort = sectorMapPort;
        this.rulesPort = rulesPort;
        this.reviewPeriod = reviewPeriod;
        this.releaseLogRepository = releaseLogRepository;
        this.termOwnership = termOwnership;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * 모든 글. 초안 단계 번호, 단계 안 순서, {@code articleId} 순이다. 대응표는 이 조회에서 한 번만 읽는다.
     *
     * <p>{@code needsUpdate}·{@code needsReview}는 공개 중인 판으로 판정한다. 초안이 아니다. 일반 사용자가 지금 보는 글이 002로
     * 넘어갈 수 있는지, 지금 보이는 제도 안내가 오래됐는지가 운영자에게 필요한 정보다.
     */
    @Transactional(readOnly = true)
    public List<AdminArticleSummaryResult> list() {
        Map<ArticleId, PublicationEvent> lastEvents = eventRepository.findLastForEachArticle();
        ListContext context =
                new ListContext(
                        stagesByKey(),
                        openSectorCodes(sectorMapPort.currentMap()),
                        today(),
                        articleRepository.findPublishedRevisions(),
                        lastEvents,
                        revisionRepository.findRevisionNos(
                                lastEvents.values().stream()
                                        .map(PublicationEvent::revisionId)
                                        .filter(Objects::nonNull)
                                        .toList()));
        return articleRepository.findAllOrdered().stream()
                .map(article -> summarize(article, context))
                .toList();
    }

    /**
     * 관리 목록이 글마다 쓰는 값. 글마다 따로 조회하지 않도록 목록 앞에서 한 번씩 읽어 둔다.
     *
     * @param published 공개 중인 판(글 → 판)
     * @param lastEvents 글마다 마지막 게시 기록
     * @param revisionNos 마지막 기록이 가리키는 판의 번호
     */
    private record ListContext(
            Map<StageKey, LearningStage> stages,
            Optional<Set<String>> openSectorCodes,
            LocalDate today,
            Map<ArticleId, ArticleRevision> published,
            Map<ArticleId, PublicationEvent> lastEvents,
            Map<Long, Integer> revisionNos) {}

    /**
     * 새 글은 본문이 빈 V2 초안이다. 읽는 순서 값은 단계 번호 × 100 + 단계 안 순서(999를 넘으면 999)다. V2 글에서는 쓰지 않지만 테이블 CHECK를
     * 지키는 값이다(data-model §13-3).
     *
     * @throws StageNotFoundException 없는 단계
     * @throws ArticleIdTakenException 이미 쓰인 {@code articleId}
     */
    public AdminArticleResult create(CreateArticleCommand command) {
        LearningStage stage =
                stageRepository
                        .findByStageKey(command.stageKey())
                        .orElseThrow(StageNotFoundException::new);
        if (articleRepository.existsByArticleKey(command.articleId())) {
            throw new ArticleIdTakenException();
        }
        int readingOrder =
                Math.min(
                        LearningArticle.MAX_READING_ORDER,
                        stage.number() * 100 + command.stagePosition());
        LearningArticle created =
                articleRepository.save(
                        LearningArticle.newDraft(
                                command.articleId(),
                                command.title(),
                                readingOrder,
                                command.placement()));
        return new AdminArticleResult(created, null, null);
    }

    /**
     * @throws ArticleNotFoundException 없는 글, 형식이 틀린 식별자
     */
    @Transactional(readOnly = true)
    public AdminArticleResult getDraft(String articleId) {
        ArticleId id = parseArticleId(articleId);
        LearningArticle article =
                articleRepository.findByArticleKey(id).orElseThrow(ArticleNotFoundException::new);
        return draftResult(article);
    }

    /**
     * 초안을 통째로 바꾼다. 공개 내용은 바뀌지 않는다(data-model §10-1).
     *
     * <p>판 번호를 먼저 본다. 옛 화면에서 온 요청이면 다른 실패보다 "새로 불러오라"가 먼저다. 그다음 단계·형식(되돌리기)·형식 모양·용어 소유(넘겨받기 포함)·문제
     * 식별자 순서로 본다.
     *
     * <p>{@code format}·{@code stageKey}·{@code stagePosition}·{@code quiz}가 없으면 지금 초안의 값을 그대로
     * 둔다(9월 판 화면 호환). 형식을 바꾸면 요청 전체가 새 형식의 모양이어야 한다.
     *
     * @throws ArticleNotFoundException 없는 글
     * @throws DraftChangedException 읽은 뒤 다른 곳에서 초안이 저장됐다
     * @throws StageNotFoundException 없는 단계
     * @throws FormatOutdatedException LEGACY 판이 없는 글을 V1로 되돌리려 했다
     * @throws InvalidDraftException 형식에 맞지 않는 요소, 지어 낸 문제·보기 식별자
     * @throws TermIdTakenException 요청의 {@code termId}를 다른 글이 초안에 두었거나 게시 중이고 넘겨받기가 없다
     * @throws TermTransferInvalidException 넘겨받기의 {@code transferFrom}이 지금 소유 글이 아니거나 다른 글이 이미 넘겨받는
     *     중이다
     */
    public AdminArticleResult saveDraft(String articleId, SaveDraftCommand command) {
        ArticleId id = parseArticleId(articleId);
        LearningArticle article =
                articleRepository
                        .findByArticleKeyForUpdate(id)
                        .orElseThrow(ArticleNotFoundException::new);
        if (!article.isDraftVersion(command.expectedDraftVersion())) {
            throw new DraftChangedException(article.draftVersion());
        }
        if (command.stageKey() != null
                && stageRepository.findByStageKey(command.stageKey()).isEmpty()) {
            throw new StageNotFoundException();
        }
        ArticleFormat format = command.format() == null ? article.format() : command.format();
        boolean hasLegacyRevision = false;
        if (article.format() == ArticleFormat.V2 && format == ArticleFormat.V1) {
            hasLegacyRevision = revisionRepository.existsByArticleIdAndEdition(id, Edition.LEGACY);
            if (!hasLegacyRevision) {
                throw new FormatOutdatedException();
            }
        }
        List<QuizQuestion> quiz = command.quiz() == null ? article.quiz() : command.quiz();
        requireFormatShape(format, command, quiz);
        termOwnership.require(id, command.terms());
        Placement placement =
                new Placement(
                        command.stageKey() == null
                                ? article.placement().stageKey()
                                : command.stageKey(),
                        command.stagePosition() == null
                                ? article.placement().position()
                                : command.stagePosition());
        LearningArticle saved =
                article.saveDraft(
                        command.expectedDraftVersion(),
                        new DraftContent(
                                command.title(),
                                command.question(),
                                command.readingOrder(),
                                format,
                                placement,
                                command.blocks(),
                                command.example(),
                                command.sources(),
                                command.terms(),
                                quiz),
                        hasLegacyRevision);
        articleRepository.save(saved);
        termRepository.replaceForArticle(id, command.terms());
        LearningArticle reloaded =
                articleRepository
                        .findByArticleKey(id)
                        .orElseThrow(
                                () -> new IllegalStateException("saved article vanished: " + id));
        return draftResult(reloaded);
    }

    /**
     * 게시 방식({@link PublishMode})을 정하고, 그 방식의 게시 전 검사와 용어 소유 판정을 통과하면 한 트랜잭션에서 게시한다({@link
     * ArticlePublisher}). 걸리면 아무것도 바꾸지 않는다.
     *
     * <p>V2 게시는 같은 문제 판정(data-model §6-3)으로 새 문제가 된 문제에 새 식별자를 주고 초안의 그 문제도 같은 식별자로 바꾼다. 넘겨받는 용어는 옛
     * 소유 글에서 가져온다(R24). 초안 번호는 올리지 않는다. 예시가 없으면 대응표를 읽지 않고 {@code NOT_CHECKED}를 남긴다.
     *
     * <p>공개된 단계에 처음 게시되는 글이면 {@code first_released_at}과 {@code ARTICLE_ADDED} 변경 기록을 같은 트랜잭션에서
     * 쓴다(R27). 다시 게시, 게시 취소 뒤 다시 게시, 공개된 단계 사이 이동은 기록하지 않는다.
     *
     * @throws ArticleNotFoundException 없는 글
     * @throws DraftChangedException 운영자가 보지 않은 초안을 게시하지 않는다
     * @throws StageNotReleasedException V2 초안인데 초안 단계가 공개 전이다
     * @throws FormatOutdatedException V1 초안인데 초안 단계가 공개됐다
     * @throws PublishCheckFailedException 검사에 걸린 항목이 있다. 모두 싣는다
     * @throws TermIdTakenException 이 글의 용어를 다른 글이 게시 중이고 넘겨받기가 없다
     * @throws TermTransferInvalidException 넘겨받기의 소유 글이 그사이 바뀌었다
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
        LearningStage stage = requireStage(article.placement().stageKey());
        PublishMode mode = PublishMode.decide(article.format(), stage.released());

        String checkVersion;
        String mapVersion;
        PublishCheckRulesV2 rulesV2 = rulesPort.v2();
        if (mode == PublishMode.LEGACY_FIX) {
            Optional<SectorMapView> map = sectorMapPort.currentMap();
            PublishCheckRules rules = rulesPort.v1();
            requirePassed(rules.version(), PublishCheck.run(article, rules, openSectorCodes(map)));
            checkVersion = rules.version();
            // 검사를 통과했으면 예시와 대응표가 모두 있다(EXAMPLE_PRESENT·EXAMPLE_SECTOR_IN_MAP).
            mapVersion = requireMapVersion(map);
        } else {
            Optional<SectorMapView> map =
                    article.example() == null ? Optional.empty() : sectorMapPort.currentMap();
            requirePassed(
                    rulesV2.version(),
                    PublishCheckV2.run(
                            article, rulesV2, openSectorCodes(map), knownTermIds(), today()));
            checkVersion = rulesV2.version();
            // 예시가 없으면 대응표를 대조하지 않았다. 판정하지 않은 것을 판정한 것처럼 적지 않는다(원칙 VI).
            mapVersion =
                    article.example() == null
                            ? ArticleRevision.NOT_CHECKED
                            : requireMapVersion(map);
        }

        // 저장 뒤 소유가 바뀌었을 수 있다(옛 소유 글이 그 용어를 빼고 게시 취소함). 넘기기 전에 다시 확인한다(R24).
        termOwnership.require(id, article.terms());

        Instant now = now();
        ArticlePublisher.Outcome outcome =
                publisher.publish(
                        article,
                        command.expectedDraftVersion(),
                        mode,
                        checkVersion,
                        mapVersion,
                        rulesV2.reading(),
                        command.actorUserId(),
                        now);
        if (outcome.firstRelease()) {
            releaseLogRepository.append(
                    ReleaseLogEntry.articleAdded(
                            stage.stageKey(),
                            id,
                            outcome.article().title(),
                            command.actorUserId(),
                            now));
        }
        return new PublishResult(
                id.value(),
                ArticleStatus.PUBLISHED,
                outcome.revisionNo(),
                checkVersion,
                mapVersion,
                now,
                mode.edition(),
                outcome.renamed(),
                outcome.transferred());
    }

    /**
     * 글과 그 글의 용어 설명을 함께 공개에서 내리고 공개 배치를 비운다. 초안 작업본은 그대로 남는다.
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

    /**
     * 지금 초안을 독자 화면과 같은 모양으로 만든다(learning-admin-api §8, FR-016). 검사하지 않고 아무것도 쓰지 않는다.
     *
     * <p>{@code edition}은 이 초안을 지금 게시하면 될 값이다(V2 → {@code CURRENT}, V1 → 첫 단계 공개 전이면 {@code
     * PRE_RELEASE}, 뒤면 {@code PREVIOUS}). 번호·다음 글은 게시 전이라 정해지지 않는다. 읽기 값은 지금 규칙 파일로, 예시는 지금 대응표로
     * 판정한다. 언급 조각의 다른 이름은 이 초안의 용어면 초안에서, 다른 글의 용어면 게시 용어 색인에서 온다.
     *
     * @throws ArticleNotFoundException 없는 글
     */
    @Transactional(readOnly = true)
    public LearningArticleResult preview(String articleId) {
        ArticleId id = parseArticleId(articleId);
        LearningArticle draft =
                articleRepository.findByArticleKey(id).orElseThrow(ArticleNotFoundException::new);
        List<LearningStage> stages = stageRepository.findAllOrdered();
        LearningStage stage =
                stages.stream()
                        .filter(
                                candidate ->
                                        candidate.stageKey().equals(draft.placement().stageKey()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "draft placement points to a missing stage: "
                                                        + draft.placement()));
        boolean v2 = draft.format() == ArticleFormat.V2;
        PublishCheckRulesV2 rules = rulesPort.v2();
        ReadingEstimate reading = PublishCheckV2.reading(draft, rules.reading());
        Set<TermId> foreign = ArticleViewAssembler.foreignMentions(draft.blocks(), draft.terms());
        Map<TermId, List<String>> foreignAliases =
                foreign.isEmpty()
                        ? Map.of()
                        : publishedTermRepository.findPublishedAliases(foreign);
        return new LearningArticleResult(
                id.value(),
                v2
                        ? "CURRENT"
                        : stages.stream().anyMatch(LearningStage::released)
                                ? "PREVIOUS"
                                : "PRE_RELEASE",
                draft.title(),
                draft.question(),
                draft.readingOrder(),
                new StageRef(stage.stageKey().value(), stage.number(), stage.name()),
                null,
                reading.readingMinutes(),
                v2 ? reading.minDwellSeconds() : null,
                ArticleViewAssembler.blocks(
                        id,
                        draft.format(),
                        draft.blocks(),
                        draft.terms(),
                        foreignAliases,
                        Mode.PREVIEW),
                ArticleViewAssembler.quiz(draft.quiz(), Mode.PREVIEW),
                ArticleViewAssembler.example(
                        id,
                        draft.format(),
                        draft.example(),
                        sectorMapPort::currentMap,
                        Mode.PREVIEW),
                draft.sources().stream()
                        .map(source -> new Source(source.label(), source.url()))
                        .toList(),
                null,
                null,
                stages.stream().anyMatch(candidate -> !candidate.released()),
                null,
                true);
    }

    private AdminArticleSummaryResult summarize(LearningArticle article, ListContext context) {
        Optional<ArticleRevision> published =
                article.status() == ArticleStatus.PUBLISHED
                        ? Optional.ofNullable(context.published().get(article.articleId()))
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
                        : needsUpdate(
                                published.get().snapshot().example(), context.openSectorCodes());
        LastPublication lastPublication =
                Optional.ofNullable(context.lastEvents().get(article.articleId()))
                        .map(
                                event ->
                                        new LastPublication(
                                                event.action(),
                                                event.revisionId() == null
                                                        ? null
                                                        : context.revisionNos()
                                                                .get(event.revisionId()),
                                                event.actorUserId(),
                                                event.occurredAt()))
                        .orElse(null);
        LearningStage stage = context.stages().get(article.placement().stageKey());
        if (stage == null) {
            throw new IllegalStateException(
                    "draft placement points to a missing stage: " + article.placement());
        }
        return new AdminArticleSummaryResult(
                article.articleId().value(),
                article.title(),
                article.readingOrder(),
                article.status(),
                article.draftVersion(),
                hasUnpublishedChanges,
                needsUpdate,
                lastPublication,
                article.format(),
                new StageSummary(stage.stageKey().value(), stage.number(), stage.released()),
                article.placement().position(),
                published.map(ArticleRevision::edition).orElse(null),
                published.map(revision -> needsReview(revision, context.today())).orElse(null),
                (int) article.terms().stream().filter(LearningTerm::transferring).count());
    }

    /**
     * 공개 중인 판의 예시가 지금 열린 대응표로 002에 넘어갈 수 없는가.
     *
     * @return 판에 예시가 없거나(V2 글은 예시가 선택이다) 대응표를 읽지 못했으면 null. 판정할 수 없는 것을 false로 세지 않는다
     */
    private static Boolean needsUpdate(
            ExampleAllocation example, Optional<Set<String>> openSectorCodes) {
        if (example == null || openSectorCodes.isEmpty()) {
            return null;
        }
        return example.sectors().stream()
                .anyMatch(sector -> !openSectorCodes.get().contains(sector.code()));
    }

    /**
     * 공개 중인 판의 제도 안내 가운데 기준 시점이 설정 기간보다 오래된 것이 있는가(research R37). 표시는 확인을 요청할 뿐 게시를 바꾸지 않는다.
     *
     * @return 판에 제도 안내가 없으면 null
     */
    private Boolean needsReview(ArticleRevision revision, LocalDate today) {
        List<InstitutionNoteBlock> notes = new ArrayList<>();
        for (ArticleBlock block : revision.snapshot().bodyBlocks()) {
            if (block instanceof InstitutionNoteBlock note) {
                notes.add(note);
            }
        }
        if (notes.isEmpty()) {
            return null;
        }
        return notes.stream()
                .anyMatch(note -> note.asOf() != null && reviewPeriod.overdue(note.asOf(), today));
    }

    /**
     * 형식 하나에만 있는 요소를 다른 형식의 초안에 쓰지 않았는가(research R16). V1 초안에 V2 블록·글자 조각·다른 이름·세 문장·확인 문제가 있거나, V2
     * 초안에 설명 문장(sentence)이나 출처 목록이 있으면 위치와 함께 거절한다.
     *
     * @param quiz 요청에 없으면 지금 초안의 문제다. V1로 되돌리면서 문제를 생략하면 V2 문제가 남으므로 거절한다
     */
    private static void requireFormatShape(
            ArticleFormat format, SaveDraftCommand command, List<QuizQuestion> quiz) {
        List<FieldViolation> violations = new ArrayList<>(command.violationsFor(format));
        if (format == ArticleFormat.V1) {
            if (command.quiz() == null && !quiz.isEmpty()) {
                violations.add(new FieldViolation("quiz", "V1 초안에는 확인 문제를 둘 수 없습니다"));
            }
            if (command.blocks().size() > ArticleBlock.MAX_BLOCKS) {
                violations.add(
                        new FieldViolation(
                                "blocks", "V1 초안의 블록은 " + ArticleBlock.MAX_BLOCKS + "개 이하여야 합니다"));
            }
        }
        if (!violations.isEmpty()) {
            throw new InvalidDraftException(violations);
        }
    }

    /**
     * 동시에 저장·게시한 다른 요청이 먼저 커밋해 UNIQUE 제약에 걸린 뒤, 어느 글이 그 {@code termId}를 가져갔는지 새 트랜잭션에서 다시 본다. 호출자는
     * 실패한 요청의 트랜잭션이 끝난 뒤(컨트롤러)에서 부른다.
     *
     * @throws ArticleNotFoundException 없는 글
     * @throws TermIdTakenException 다른 글이 초안에 두었거나 게시 중인 용어가 있다
     * @throws TermTransferInvalidException 다른 글이 이미 넘겨받는 중인 용어가 있다
     */
    @Transactional(readOnly = true)
    public void requireTermIdsAvailable(String articleId, List<LearningTerm> terms) {
        termOwnership.require(parseArticleId(articleId), terms);
    }

    /** 언급 조각이 가리킬 수 있는 용어: 모든 글의 초안 용어와 게시 용어 색인의 합집합(data-model §8-2 #13). */
    private Set<TermId> knownTermIds() {
        Set<TermId> known = new HashSet<>(termRepository.findAllTermIds());
        known.addAll(publishedTermRepository.findAllTermIds());
        return known;
    }

    private static void requirePassed(String checkVersion, PublishCheckResult checked) {
        if (!checked.passed()) {
            throw new PublishCheckFailedException(checkVersion, checked.failures());
        }
    }

    private static String requireMapVersion(Optional<SectorMapView> map) {
        return map.map(SectorMapView::mapVersion)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "publish check passed without a sector map"));
    }

    /** 초안 배치는 외래키로 단계를 가리킨다. 없으면 저장 데이터가 깨진 것이다. */
    private LearningStage requireStage(StageKey stageKey) {
        return stageRepository
                .findByStageKey(stageKey)
                .orElseThrow(
                        () -> new IllegalStateException("learning stage not found: " + stageKey));
    }

    private Map<StageKey, LearningStage> stagesByKey() {
        return stageRepository.findAllOrdered().stream()
                .collect(Collectors.toMap(LearningStage::stageKey, Function.identity()));
    }

    private AdminArticleResult draftResult(LearningArticle article) {
        Optional<ArticleRevision> published =
                articleRepository.findPublishedRevision(article.articleId());
        return new AdminArticleResult(
                article,
                published.map(ArticleRevision::revisionNo).orElse(null),
                published.map(ArticleRevision::edition).orElse(null));
    }

    private static Optional<Set<String>> openSectorCodes(Optional<SectorMapView> map) {
        return map.map(view -> view.sectorNamesByCode().keySet());
    }

    /** DB가 마이크로초까지 저장하므로 응답 값과 저장 값이 같도록 자른다. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** 게시 전 검사와 '확인 필요'의 기준 날짜. 서비스가 운영되는 서울 날짜다. */
    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SEOUL);
    }

    private static ArticleId parseArticleId(String raw) {
        try {
            return new ArticleId(raw);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ArticleNotFoundException();
        }
    }
}
