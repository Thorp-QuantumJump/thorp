package com.ssafy.thorp.learning.application.service;

import com.ssafy.thorp.learning.application.exception.ArticleUnavailableException;
import com.ssafy.thorp.learning.application.exception.TermNotReadyException;
import com.ssafy.thorp.learning.application.port.PublishCheckRulesPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.LearningStageRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.repository.ReleaseLogRepository;
import com.ssafy.thorp.learning.application.result.CurrentArticleEntry;
import com.ssafy.thorp.learning.application.result.CurriculumResult;
import com.ssafy.thorp.learning.application.result.CurriculumResult.LogLine;
import com.ssafy.thorp.learning.application.result.CurriculumResult.OpenArticle;
import com.ssafy.thorp.learning.application.result.CurriculumResult.PreviousArticle;
import com.ssafy.thorp.learning.application.result.CurriculumResult.StageStatus;
import com.ssafy.thorp.learning.application.result.CurriculumResult.StageView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.NextArticle;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.Source;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.StageRef;
import com.ssafy.thorp.learning.application.result.LearningArticleSummaryResult;
import com.ssafy.thorp.learning.application.result.LearningTermResult;
import com.ssafy.thorp.learning.application.result.PublishedArticleEntry;
import com.ssafy.thorp.learning.application.result.PublishedArticleView;
import com.ssafy.thorp.learning.application.service.ArticleViewAssembler.Mode;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.ArticleSnapshot;
import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.LearningStage;
import com.ssafy.thorp.learning.domain.model.ReadingEstimate;
import com.ssafy.thorp.learning.domain.model.ReleaseLogEntry;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배우기 공개 조회 유스케이스(US1·US3 서버, learning-public-api).
 *
 * <p><b>공개 중인 판, 공개 상태(단계의 공개 여부, 글의 공개 배치)와 게시 용어 색인만 읽는다.</b> 초안 작업본({@code learning_article}의 본문
 * 컬럼, {@code learning_term})으로 응답을 만들지 않는다. 게시 뒤 초안을 고쳐도 공개 내용이 바뀌지 않아야 한다(SC-006).
 *
 * <p>사용자를 식별하지 않고 조회를 사용자별로 남기지 않는다(FR-012).
 */
@Service
@Transactional(readOnly = true)
public class LearningQueryService {

    /** 공개 안내를 보이는 기간. 변경 기록 가운데 지금부터 이 기간 안의 줄이다(FR-031, research R27). */
    static final Duration NOTICE_PERIOD = Duration.ofDays(7);

    private final LearningArticleRepository articleRepository;
    private final PublishedTermRepository publishedTermRepository;
    private final LearningStageRepository stageRepository;
    private final ReleaseLogRepository releaseLogRepository;
    private final SectorMapPort sectorMapPort;
    private final PublishCheckRulesPort rulesPort;
    private final Clock clock;

    public LearningQueryService(
            LearningArticleRepository articleRepository,
            PublishedTermRepository publishedTermRepository,
            LearningStageRepository stageRepository,
            ReleaseLogRepository releaseLogRepository,
            SectorMapPort sectorMapPort,
            PublishCheckRulesPort rulesPort,
            Clock clock) {
        this.articleRepository = articleRepository;
        this.publishedTermRepository = publishedTermRepository;
        this.stageRepository = stageRepository;
        this.releaseLogRepository = releaseLogRepository;
        this.sectorMapPort = sectorMapPort;
        this.rulesPort = rulesPort;
        this.clock = clock;
    }

    /**
     * 공개 중인 글(LEGACY·CURRENT 모두). 판의 {@code readingOrder}, {@code articleId} 오름차순이다. 9월 판 목록 경로를 지운
     * 뒤(learning-public-api §2) 지금 부르는 곳은 없다. 017 plan '근거 팩과 도구'의 {@code readArticle}이 게시 글 열거에 쓸
     * 조회라 017 구현 전에도 남긴다(tasks ⑧·T197).
     *
     * @return 게시된 글이 없으면 빈 목록
     */
    public List<LearningArticleSummaryResult> listPublished() {
        return articleRepository.findPublishedOrdered();
    }

    /**
     * 커리큘럼(learning-public-api §1). 여섯 단계와 공개 상태, 공개된 단계의 공개된 글(읽는 순서·번호), 공개 전 단계에 남은 이전 판 글, 변경
     * 기록과 지난 7일의 공개 안내다.
     *
     * <p><b>공개되지 않은 단계의 새 글은 어떤 필드로도 내보내지 않는다</b>(SC-017). 공개 전 단계에는 이전 판(LEGACY) 글만 싣고, 그것도 첫 단계를
     * 공개한 뒤에만 싣는다. 첫 단계를 공개하기 전에는 9월 판 목록과 같은 {@code preReleaseArticles}만 채운다(FR-031 "지금 모습 그대로").
     *
     * <p>단계·판·공개 배치·색인·변경 기록을 여러 문장으로 읽으므로 한 스냅숏({@code REPEATABLE_READ})에서 읽는다. 그사이 단계 공개가 커밋되면
     * 단계는 공개됐는데 글이 없는 응답이 된다.
     *
     * <p>판 본문을 읽지 않는다({@link PublishedArticleEntry}). 본문이 필요한 것은 공개 전 단계에 남은 이전 판 글의 읽기 분뿐이라 그 글만
     * 따로 읽는다. 글 번호는 글 화면({@link #getPublished})과 같은 저장소 순서로 매긴다.
     *
     * @return 게시된 글이 없어도 정상 응답이다(spec Edge Cases)
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CurriculumResult curriculum() {
        List<LearningStage> stages = stageRepository.findAllOrdered();
        int releasedStageCount = (int) stages.stream().filter(LearningStage::released).count();
        // 첫 단계를 공개하기 전에는 모든 글이 preReleaseArticles에 있다. 단계마다 나눌 글을 읽지 않는다.
        List<PublishedArticleEntry> entries =
                releasedStageCount == 0 ? List.of() : articleRepository.findPublishedEntries();
        Map<StageKey, List<PublishedArticleEntry>> currentByStage = new HashMap<>();
        Map<StageKey, List<PublishedArticleEntry>> legacyByStage = new HashMap<>();
        for (PublishedArticleEntry entry : entries) {
            (entry.edition() == Edition.CURRENT ? currentByStage : legacyByStage)
                    .computeIfAbsent(entry.stageKey(), key -> new ArrayList<>())
                    .add(entry);
        }
        Map<ArticleId, Integer> termCounts =
                currentByStage.isEmpty()
                        ? Map.of()
                        : publishedTermRepository.countPublishedByArticle();
        Map<ArticleId, ArticleRevision> previousRevisions =
                articleRepository.findPublishedRevisions(
                        stages.stream()
                                .filter(stage -> !stage.released())
                                .flatMap(
                                        stage ->
                                                legacyByStage
                                                        .getOrDefault(stage.stageKey(), List.of())
                                                        .stream())
                                .map(PublishedArticleEntry::articleId)
                                .toList());
        int charsPerMinute = rulesPort.v2().reading().charsPerMinute();

        List<StageView> views = new ArrayList<>();
        for (LearningStage stage : stages) {
            String key = stage.stageKey().value();
            List<OpenArticle> open = List.of();
            List<PreviousArticle> previous = List.of();
            StageStatus status;
            if (stage.released()) {
                // 저장소가 읽는 순서(단계 안 순서, articleId)로 준다. 그 순번이 글 번호다.
                List<PublishedArticleEntry> current =
                        currentByStage.getOrDefault(stage.stageKey(), List.of());
                List<OpenArticle> numbered = new ArrayList<>();
                for (int index = 0; index < current.size(); index++) {
                    PublishedArticleEntry entry = current.get(index);
                    numbered.add(
                            new OpenArticle(
                                    entry.articleId().value(),
                                    stage.number() + "-" + (index + 1),
                                    entry.title(),
                                    entry.question(),
                                    entry.readingMinutes(),
                                    termCounts.getOrDefault(entry.articleId(), 0),
                                    entry.hasExample()));
                }
                open = numbered;
                status = open.isEmpty() ? StageStatus.REPREPARING : StageStatus.OPEN;
            } else {
                status = StageStatus.PREPARING;
                previous =
                        legacyByStage.getOrDefault(stage.stageKey(), List.of()).stream()
                                .map(
                                        entry ->
                                                previousArticle(
                                                        entry,
                                                        previousRevisions.get(entry.articleId()),
                                                        charsPerMinute))
                                .toList();
            }
            views.add(
                    new StageView(
                            key,
                            stage.number(),
                            stage.name(),
                            stage.levelLabel(),
                            stage.summary(),
                            status,
                            stage.releasedAt(),
                            open,
                            previous));
        }

        Map<StageKey, LearningStage> stagesByKey = new HashMap<>();
        stages.forEach(stage -> stagesByKey.put(stage.stageKey(), stage));
        List<LogLine> changeLog =
                releaseLogRepository.findAllLatestFirst().stream()
                        .map(entry -> logLine(entry, stagesByKey))
                        .toList();
        Instant noticeFrom = clock.instant().minus(NOTICE_PERIOD);
        return new CurriculumResult(
                releasedStageCount,
                releasedStageCount == stages.size(),
                views,
                releasedStageCount == 0 ? articleRepository.findPublishedOrdered() : null,
                changeLog.stream().filter(line -> !line.occurredAt().isBefore(noticeFrom)).toList(),
                changeLog);
    }

    /**
     * 공개 중인 글 하나(learning-public-api §3).
     *
     * <p>{@code edition}은 판의 종류와 단계 공개 상태로 정한다. {@code CURRENT} 판은 {@code CURRENT}, {@code LEGACY}
     * 판은 공개된 단계가 하나도 없으면 {@code PRE_RELEASE}(지금 모습 그대로), 있으면 {@code PREVIOUS}(이전 판)다(R16). {@code
     * LEGACY} 판의 블록은 9월 판 모양 그대로다.
     *
     * <p>판·공개 배치·읽는 순서·게시 색인을 여러 문장으로 읽으므로 한 스냅숏({@code REPEATABLE_READ})에서 읽는다. 기본 격리 수준이면 그 사이에
     * 커밋된 게시 취소가 공개 배치를 비워 404가 아닌 500이 되거나, 다시 게시한 글의 옛 판과 새 배치가 짝지어진다.
     *
     * @throws ArticleUnavailableException 없는 글, 게시되지 않은 글, 형식이 틀린 식별자. 셋을 가르지 않는다
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LearningArticleResult getPublished(String articleId) {
        PublishedArticleView view =
                parseArticleId(articleId)
                        .flatMap(articleRepository::findPublished)
                        .orElseThrow(ArticleUnavailableException::new);
        ArticleRevision revision = view.revision();
        ArticleSnapshot snapshot = revision.snapshot();
        // 공개 판 포인터가 다른 글의 판을 가리키면 그 글의 본문을 이 주소로 내보내게 된다.
        // 스키마가 막지 않는 관계라 여기서 확인하고, 어긋나면 조용히 보이지 않고 실패시킨다.
        if (!snapshot.articleId().value().equals(articleId)) {
            throw new IllegalStateException(
                    "published revision of "
                            + articleId
                            + " belongs to another article: "
                            + snapshot.articleId());
        }
        List<LearningStage> stages = stageRepository.findAllOrdered();
        LearningStage stage = stage(stages, view);
        boolean current = revision.edition() == Edition.CURRENT;

        String number = null;
        NextArticle next = null;
        Integer stageArticleCount = null;
        if (current) {
            List<CurrentArticleEntry> order = articleRepository.findCurrentInReadingOrder();
            int position = indexOf(order, articleId);
            CurrentArticleEntry self = order.get(position);
            number = number(order, self);
            if (position + 1 < order.size()) {
                CurrentArticleEntry following = order.get(position + 1);
                next =
                        new NextArticle(
                                following.articleId(), number(order, following), following.title());
            }
            stageArticleCount =
                    (int)
                            order.stream()
                                    .filter(entry -> entry.stageKey().equals(self.stageKey()))
                                    .count();
        }

        Set<TermId> foreign =
                current
                        ? ArticleViewAssembler.foreignMentions(
                                snapshot.bodyBlocks(), snapshot.terms())
                        : Set.of();
        Map<TermId, List<String>> foreignAliases =
                foreign.isEmpty()
                        ? Map.of()
                        : publishedTermRepository.findPublishedAliases(foreign);
        return new LearningArticleResult(
                snapshot.articleId().value(),
                current
                        ? "CURRENT"
                        : stages.stream().anyMatch(LearningStage::released)
                                ? "PREVIOUS"
                                : "PRE_RELEASE",
                snapshot.title(),
                snapshot.question(),
                snapshot.readingOrder(),
                new StageRef(stage.stageKey().value(), stage.number(), stage.name()),
                number,
                current
                        ? snapshot.reading().readingMinutes()
                        : ReadingEstimate.minutes(
                                ReadingEstimate.bodyChars(snapshot.bodyBlocks(), snapshot.terms()),
                                rulesPort.v2().reading().charsPerMinute()),
                current ? snapshot.reading().minDwellSeconds() : null,
                ArticleViewAssembler.blocks(
                        snapshot.articleId(),
                        snapshot.format(),
                        snapshot.bodyBlocks(),
                        snapshot.terms(),
                        foreignAliases,
                        Mode.PUBLISHED),
                ArticleViewAssembler.quiz(snapshot.quiz(), Mode.PUBLISHED),
                ArticleViewAssembler.example(
                        snapshot.articleId(),
                        snapshot.format(),
                        snapshot.example(),
                        sectorMapPort::currentMap,
                        Mode.PUBLISHED),
                snapshot.sources().stream()
                        .map(source -> new Source(source.label(), source.url()))
                        .toList(),
                next,
                stageArticleCount,
                stages.stream().anyMatch(candidate -> !candidate.released()),
                revision.publishedAt(),
                false);
    }

    /**
     * 공개 중인 용어 설명 하나. 게시 용어 색인만 읽는다.
     *
     * @throws TermNotReadyException 게시된 설명이 없거나 형식이 틀린 식별자. 둘을 가르지 않는다
     */
    public LearningTermResult getPublishedTerm(String termId) {
        return parseTermId(termId)
                .flatMap(publishedTermRepository::findPublished)
                .orElseThrow(TermNotReadyException::new);
    }

    /**
     * 이전 판 글의 읽기 분은 판에 없으므로 조회 때 계산한다. 글 하나 조회와 같은 식이다.
     *
     * @param revision 같은 스냅숏에서 읽은 그 글의 공개 중인 판
     */
    private static PreviousArticle previousArticle(
            PublishedArticleEntry entry, ArticleRevision revision, int charsPerMinute) {
        if (revision == null) {
            throw new IllegalStateException(
                    "published revision vanished in one snapshot: " + entry.articleId());
        }
        ArticleSnapshot snapshot = revision.snapshot();
        return new PreviousArticle(
                entry.articleId().value(),
                entry.title(),
                entry.question(),
                ReadingEstimate.minutes(
                        ReadingEstimate.bodyChars(snapshot.bodyBlocks(), snapshot.terms()),
                        charsPerMinute));
    }

    private static LogLine logLine(ReleaseLogEntry entry, Map<StageKey, LearningStage> stages) {
        LearningStage stage = stages.get(entry.stageKey());
        if (stage == null) {
            throw new IllegalStateException(
                    "release log points to a missing stage: " + entry.entryKey());
        }
        return new LogLine(
                entry.entryKey(),
                entry.kind(),
                stage.number(),
                stage.name(),
                entry.occurredAt(),
                entry.newCount(),
                entry.rewrittenCount(),
                entry.rewrittenTitles(),
                entry.articleTitle());
    }

    /**
     * 글 번호 {@code {단계 번호}-{그 단계의 공개된 글 가운데 읽는 순서 순번}}(data-model §12-2). {@code order}는 읽는 순서라 같은
     * 단계 글이 이어져 있다.
     */
    private static String number(List<CurrentArticleEntry> order, CurrentArticleEntry entry) {
        int ordinal = 0;
        for (CurrentArticleEntry candidate : order) {
            if (candidate.stageKey().equals(entry.stageKey())) {
                ordinal++;
            }
            if (candidate.articleId().equals(entry.articleId())) {
                return entry.stageNumber() + "-" + ordinal;
            }
        }
        throw new IllegalStateException("article is not in the reading order: " + entry);
    }

    /** CURRENT 판이 게시 중인 글이면 읽는 순서에 있다. 없으면 글 행과 판이 어긋난 것이라 실패시킨다. */
    private static int indexOf(List<CurrentArticleEntry> order, String articleId) {
        for (int index = 0; index < order.size(); index++) {
            if (order.get(index).articleId().equals(articleId)) {
                return index;
            }
        }
        throw new IllegalStateException(
                "current article is missing from the reading order: " + articleId);
    }

    private static LearningStage stage(List<LearningStage> stages, PublishedArticleView view) {
        return stages.stream()
                .filter(stage -> stage.stageKey().equals(view.placement().stageKey()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "published placement points to a missing stage: "
                                                + view.placement()));
    }

    private static Optional<ArticleId> parseArticleId(String raw) {
        return ArticleId.parse(raw);
    }

    private static Optional<TermId> parseTermId(String raw) {
        try {
            return Optional.of(new TermId(raw));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
    }
}
